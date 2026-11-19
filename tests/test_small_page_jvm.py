# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import tempfile
import unittest
from contextlib import contextmanager, nullcontext
from errno import EINVAL
from pathlib import Path
from threading import Event
from types import SimpleNamespace
from unittest.mock import Mock, patch

import numpy as np

from sheet_music_recognition import document, reader, runtime


class SmallPageJvmTest(unittest.TestCase):
    def test_reusable_decoder_is_lazy_and_closes_after_nested_reads(self):
        engine = reader.Interpreter.__new__(reader.Interpreter)
        engine.jar = Path("interpreter.jar")
        calls = []
        closed = []

        @contextmanager
        def pipe(_java, _jar):
            try:
                yield calls.append, calls.append
            finally:
                closed.append(True)

        with (patch.object(reader, "java_executable", return_value="java"),
              patch.object(reader, "ready_page_decoder", return_value=nullcontext(None)),
              patch.object(document, "_decoder_pipe", side_effect=pipe) as launch):
            with engine.reuse_decoder() as scoped:
                self.assertIs(scoped, engine)
                launch.assert_not_called()
                self.assertTrue(engine._try_reusable_decoder(["first"], 450_000))
                with engine.reuse_decoder():
                    self.assertTrue(engine._try_reusable_decoder(["second"], 450_000))
                self.assertFalse(closed)
                with document._document_page_decoder(engine) as decode:
                    self.assertIs(decode.__self__, engine)
                launch.assert_called_once()
            self.assertEqual([True], closed)
            self.assertFalse(engine._try_reusable_decoder(["outside"], 450_000))
        self.assertEqual([["first"], ["second"]], calls)

    def test_reusable_decoder_releases_the_archive_after_its_first_response(self):
        engine = reader.Interpreter.__new__(reader.Interpreter)
        engine.jar = Path("interpreter.jar")
        archive = ["java", "-Xmx1g", "-Xshare:on", "-XX:SharedArchiveFile=test.jsa"]
        events = []

        @contextmanager
        def ready(jar, pixels, *, short_tier):
            self.assertEqual((engine.jar, 450_000, False), (jar, pixels, short_tier))
            events.append("lease")
            try:
                yield archive
            finally:
                events.append("release")

        @contextmanager
        def pipe(java, _jar):
            self.assertEqual(archive, java)
            try:
                yield None, lambda args: events.append(args[0])
            finally:
                events.append("close")

        with (patch.object(reader, "ready_page_decoder", side_effect=ready),
              patch.object(document, "_decoder_pipe", side_effect=pipe)):
            with engine.reuse_decoder():
                self.assertTrue(engine._try_reusable_decoder(["first"], 450_000))
                self.assertEqual(["lease", "first", "release"], events)
                self.assertTrue(engine._try_reusable_decoder(["second"], 650_000))
                self.assertEqual(["lease", "first", "release", "second"], events)
        self.assertEqual(["lease", "first", "release", "second", "close"], events)

    def test_failed_reusable_decoder_retries_the_same_page_and_reopens(self):
        engine = reader.Interpreter.__new__(reader.Interpreter)
        engine.jar = Path("interpreter.jar")
        engine.model_sha256 = "model"
        paper = SimpleNamespace(changed=False, raw_sha256="raw", decoder_sha256="raw")
        prepared = reader._PreparedPage(SimpleNamespace(size=200), None, {}, paper)
        packet_log = []
        closed = []

        def worker(arguments):
            packet_log.append(("worker", arguments))
            if len(packet_log) == 1:
                raise document._DecoderPipeError("worker exited")
            Path(arguments[1]).write_text('{"events": []}', encoding="utf-8")

        def ordinary(_java, _jar, _pixels, arguments):
            packet_log.append(("ordinary", arguments))
            Path(arguments[1]).write_text('{"events": []}', encoding="utf-8")
            return SimpleNamespace(returncode=0)

        @contextmanager
        def pipe(_java, _jar):
            try:
                yield None, worker
            finally:
                closed.append(True)

        with (patch.object(reader, "java_executable", return_value="java"),
              patch.object(reader, "ready_page_decoder", return_value=nullcontext(None)),
              patch.object(document, "_decoder_pipe", side_effect=pipe) as launch,
              patch.object(reader, "write_page"),
              patch.object(reader, "run_page_decoder", side_effect=ordinary)):
            with engine.reuse_decoder():
                first = engine._decode_page(prepared, (4, 4), 0, None, None)
                second = engine._decode_page(prepared, (4, 4), 0, None, None)
                self.assertEqual(first, second)
                self.assertEqual(2, launch.call_count)
                self.assertEqual([True], closed)
            self.assertEqual([True, True], closed)
        self.assertEqual(["worker", "ordinary", "worker"], [kind for kind, _ in packet_log])
        self.assertEqual(packet_log[0][1], packet_log[1][1])

    def test_finished_ocr_launches_one_decoder_while_prediction_is_pending(self):
        engine = reader.Interpreter.__new__(reader.Interpreter)
        engine.jar = Path("interpreter.jar")
        gray = np.full((16, 32), 255, np.uint8)
        engine.paper_whitening = SimpleNamespace(correct=Mock())

        def run_page(_arguments):
            pass

        for startup_fails in (False, True):
            with self.subTest(startup_fails=startup_fails):
                started = Event()

                def predict(image):
                    self.assertIs(image, gray)
                    self.assertTrue(started.wait(5))
                    return np.zeros_like(image)

                @contextmanager
                def pipe(_java, _jar):
                    started.set()
                    if startup_fails:
                        raise OSError("startup failed")
                    yield Mock(), run_page

                with (patch.object(engine, "predict", side_effect=predict),
                      patch.object(engine, "_read_annotations", return_value={"words": []}),
                      patch.object(engine, "_decode_page", return_value="score") as decode,
                      patch.object(reader, "grayscale", return_value=gray),
                      patch.object(reader, "ready_page_decoder", return_value=nullcontext(["java"])),
                      patch.object(reader, "write_page"),
                      patch.object(reader, "_write_decoder_raster", return_value=None),
                      patch.object(document, "_decoder_pipe", side_effect=pipe) as launch):
                    self.assertEqual("score", engine.interpret(None))
                launch.assert_called_once()
                self.assertIs(None if startup_fails else run_page,
                              decode.call_args.kwargs.get("run_decoder"))

    def test_failed_geometry_pipe_reuses_the_prepared_page_for_ordinary_decode(self):
        engine = reader.Interpreter.__new__(reader.Interpreter)
        engine.jar = Path("interpreter.jar")
        prepared = object()
        sent = []
        calls = []

        def prepare(_image, _width, _annotations, *, prepare_geometry, start_decoder):
            prepare_geometry(SimpleNamespace(size=200), None, SimpleNamespace(changed=False))
            return prepared

        def failed_pipe(_args):
            raise document._DecoderPipeError("pipe closed")

        def decode(page, *_args, run_decoder=None):
            self.assertIs(page, prepared)
            calls.append(run_decoder)
            if run_decoder is not None:
                run_decoder([])
            return "ordinary score"

        with (patch.object(engine, "_prepare_page", side_effect=prepare) as predict,
              patch.object(engine, "_decode_page", side_effect=decode),
              patch.object(reader, "ready_page_decoder", return_value=nullcontext(["java"])),
              patch.object(reader, "write_page"),
              patch.object(document, "_decoder_pipe",
                           return_value=nullcontext((sent.append, failed_pipe)))):
            self.assertEqual("ordinary score", engine.interpret(None))
        predict.assert_called_once()
        self.assertEqual([failed_pipe, None], calls)
        self.assertEqual(1, len(sent))
        self.assertEqual("", sent[0][1])

    def test_exited_decoder_pipe_accepts_windows_invalid_argument_at_cleanup(self):
        stdin = Mock()
        stdin.flush.side_effect = OSError(EINVAL, "Invalid argument")
        stdin.close.side_effect = OSError(EINVAL, "Invalid argument")
        child = SimpleNamespace(stdin=stdin, stdout=Mock(), wait=Mock(), returncode=1)

        def launch(_command, *, stdin, stdout, stderr):
            stderr.write(b"HotSpot startup failed")
            return nullcontext(child)

        with patch.object(document.subprocess, "Popen", side_effect=launch):
            with document._decoder_pipe(["java"], Path("interpreter.jar")) as (_, decode):
                with self.assertRaisesRegex(document._DecoderPipeError, "HotSpot startup failed"):
                    decode(("page", "output"))
        child.wait.assert_called_once()

    def test_archive_trains_after_first_read_and_a_bad_archive_retries_once(self):
        with tempfile.TemporaryDirectory() as folder:
            cache = Path(folder)
            java = cache / "bin" / "java.exe"
            stock = java.parent / "server" / "classes.jsa"
            stock.parent.mkdir(parents=True)
            stock.touch()
            calls = []
            good = SimpleNamespace(returncode=0)
            bad = SimpleNamespace(returncode=1)

            def launch(_command, options=()):
                calls.append(options)
                if options and options[-1].startswith("-XX:ArchiveClassesAtExit="):
                    Path(options[-1].split("=", 1)[1]).touch()
                if options and options[-1].startswith("-XX:SharedArchiveFile="):
                    return bad
                return good

            with patch.object(runtime, "_run_java", side_effect=launch):
                for _ in range(4):
                    self.assertIs(good, runtime._use_or_train_archive([], java, cache, "key"))
            self.assertEqual((), calls[0])
            self.assertTrue(calls[1][-1].startswith("-XX:ArchiveClassesAtExit="))
            self.assertTrue(calls[2][-1].startswith("-XX:SharedArchiveFile="))
            self.assertEqual([(), ()], calls[3:])

    def test_only_short_runs_on_the_qualified_runtime_use_first_tier(self):
        with tempfile.TemporaryDirectory() as folder:
            home = Path(folder)
            java = home / "bin" / "java.exe"
            with patch.object(runtime.sys, "platform", "win32"), patch.dict(runtime.os.environ, clear=True):
                self.assertEqual((), runtime.small_page_jvm_args(java, 483_328))
                (home / "release").write_text(
                    'IMPLEMENTOR="JetBrains s.r.o."\n'
                    'JAVA_RUNTIME_VERSION="21.0.10+-14961533-b1163.108"\n',
                    encoding="utf-8",
                )
                self.assertEqual(("-XX:TieredStopAtLevel=1",), runtime.small_page_jvm_args(java, 500_000))
                self.assertEqual((), runtime.small_page_jvm_args(java, 500_001))
                with patch.dict(runtime.os.environ, {"JAVA_TOOL_OPTIONS": "-XX:TieredStopAtLevel=3"}):
                    self.assertEqual((), runtime.small_page_jvm_args(java, 483_328))
                (home / "release").write_text('IMPLEMENTOR="JetBrains s.r.o."\n', encoding="utf-8")
                self.assertEqual((), runtime.small_page_jvm_args(java, 483_328))

    def test_requires_the_qualified_temurin_or_openjdk_descriptor(self):
        with tempfile.TemporaryDirectory() as folder:
            home = Path(folder)
            java = home / "bin" / "java.exe"
            with patch.object(runtime.sys, "platform", "win32"), patch.dict(runtime.os.environ, clear=True):
                for descriptor in (
                    'IMPLEMENTOR="Eclipse Adoptium"\nJAVA_RUNTIME_VERSION="17.0.20.1+1"\n',
                    'IMPLEMENTOR="Oracle Corporation"\nJAVA_VERSION="19.0.1"\nSOURCE=".:git:aa986c51a096"\n',
                ):
                    with self.subTest(descriptor=descriptor):
                        (home / "release").write_text(descriptor, encoding="utf-8")
                        self.assertEqual(("-XX:TieredStopAtLevel=1",), runtime.small_page_jvm_args(java, 135_000))
                (home / "release").write_text(
                    'IMPLEMENTOR="Oracle Corporation"\nJAVA_VERSION="19.0.1"\n', encoding="utf-8"
                )
                self.assertEqual((), runtime.small_page_jvm_args(java, 135_000))
