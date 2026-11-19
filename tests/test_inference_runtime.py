# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import struct
import sys
import unittest
from pathlib import Path
from threading import Event
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np

from sheet_music_recognition import reader
from sheet_music_recognition.runtime import inference_interpreter


class InferenceRuntimeTest(unittest.TestCase):
    def test_deferred_bundled_runtime_starts_once_on_first_prediction(self):
        class Model:
            def __init__(self, model_path, num_threads):
                self.threads = num_threads

            def allocate_tensors(self):
                pass

            def get_input_details(self):
                return [{"shape": np.array([1, 3, 320, 320]), "dtype": np.float32, "index": 0}]

            def get_output_details(self):
                return [{"shape": np.array([1, 320, 320]), "dtype": np.int64, "index": 0}]

            def set_tensor(self, index, tensor):
                pass

            def invoke(self):
                pass

            def get_tensor(self, index):
                return np.zeros((1, 320, 320), dtype=np.int64)

        with patch.object(reader, "inference_interpreter", return_value=Model) as factory:
            engine = reader.Interpreter(threads=2, _defer_model=True)
            factory.assert_not_called()
            pixels = np.full((8, 8), 255, np.uint8)
            np.testing.assert_array_equal(engine.predict(pixels), np.zeros_like(pixels))
            np.testing.assert_array_equal(engine.predict(pixels), np.zeros_like(pixels))
            factory.assert_called_once_with(bundled=True)
            self.assertEqual(2, engine.runtime.threads)
            self.assertIsNone(engine.ocr)

    def test_short_deferred_runtime_reuses_its_arena_and_expands_for_a_taller_page(self):
        loads = []

        class Model:
            refuse_short = False

            def __init__(self, *, num_threads, model_path=None, model_content=None):
                self.height = 320 if model_content is None else struct.unpack_from("<i", model_content, 1124)[0]
                loads.append((self.height, num_threads))
                if self.refuse_short and model_content is not None:
                    raise RuntimeError("This runtime refused the short arena")

            def allocate_tensors(self):
                pass

            def get_input_details(self):
                return [{"shape": np.array([1, 3, self.height, 320]), "dtype": np.float32, "index": 0}]

            def get_output_details(self):
                return [{"shape": np.array([1, self.height, 320]), "dtype": np.int64, "index": 1}]

            def set_tensor(self, index, tensor):
                pass

            def invoke(self):
                pass

            def get_tensor(self, index):
                return np.zeros((1, self.height, 320), np.int64)

        Model.__module__ = "ai_edge_litert.interpreter"
        with patch.object(reader, "inference_interpreter", return_value=Model):
            engine = reader.Interpreter(threads=2, _defer_model=True)
            for height in (224, 208, 576, 224):
                pixels = np.full((height, 320), 255, np.uint8)
                np.testing.assert_array_equal(engine.predict(pixels), np.zeros_like(pixels))
            self.assertEqual([(304, 2)], loads)
            self.assertEqual(304, engine._model_height)

            # One off-white pixel on a taller page still needs the original model.
            taller = np.full((225, 320), 255, np.uint8)
            taller[-1, -1] = 254
            engine.predict(taller)

        self.assertEqual([(304, 2), (320, 2)], loads)
        self.assertEqual(320, engine._model_height)

        loads.clear()
        Model.refuse_short = True
        with patch.object(reader, "inference_interpreter", return_value=Model):
            engine = reader.Interpreter(threads=2, _defer_model=True)
            np.testing.assert_array_equal(engine.predict(pixels), np.zeros_like(pixels))
        self.assertEqual([(304, 2), (320, 2)], loads)
        self.assertEqual(320, engine._model_height)

    def test_final_cli_prediction_can_release_litert_while_ocr_is_pending(self):
        gray = np.full((8, 8), 255, np.uint8)
        for release in (False, True):
            with self.subTest(release=release):
                engine = reader.Interpreter.__new__(reader.Interpreter)
                engine.runtime = type("LiteRT", (), {"__module__": "ai_edge_litert.interpreter"})()
                engine._sparse_pairs_enabled = True
                engine.model_sha256 = reader.MODEL_SHA256
                whitening_done = Event()

                def annotations(*_args):
                    self.assertTrue(whitening_done.wait(5))
                    return {"words": []}

                def correct(pixels, labels):
                    try:
                        self.assertEqual(not release, hasattr(engine, "runtime"))
                    finally:
                        whitening_done.set()
                    return reader.WhiteningResult(pixels, False, "raw", "raw")

                engine.paper_whitening = SimpleNamespace(correct=correct)
                with (patch.object(reader, "grayscale", return_value=gray),
                      patch.object(engine, "_read_annotations", side_effect=annotations),
                      patch.object(engine, "predict", return_value=np.zeros_like(gray))):
                    prepared = engine._prepare_page(None, 64, None, _release_after_prediction=release)

                np.testing.assert_array_equal(np.zeros_like(gray), prepared.labels)
                self.assertEqual({"words": []}, prepared.annotations)
                self.assertEqual(reader.MODEL_SHA256, engine.model_sha256)
                if release:
                    self.assertEqual((Path(reader.__file__).parent / "data" / reader.MODEL_NAME, True),
                                     engine._pending_model)

    def test_qualified_windows_runtime_is_used_only_for_the_bundled_model(self):
        lite, tf = object(), object()
        modules = {"ai_edge_litert.interpreter": SimpleNamespace(Interpreter=lite),
                   "tensorflow": SimpleNamespace(lite=SimpleNamespace(Interpreter=tf))}
        with (patch.dict(sys.modules, modules), patch("sys.platform", "win32"),
              patch("platform.machine", return_value="AMD64"),
              patch("importlib.metadata.version", return_value="2.3.0")):
            self.assertIs(lite, inference_interpreter(bundled=True))
            self.assertIs(tf, inference_interpreter(bundled=False))
            with patch("platform.machine", return_value="ARM64"):
                self.assertIs(tf, inference_interpreter(bundled=True))

    def test_existing_runtime_handles_missing_or_unqualified_installations(self):
        tf = object()
        modules = {"ai_edge_litert.interpreter": None,
                   "tensorflow": SimpleNamespace(lite=SimpleNamespace(Interpreter=tf))}
        with (patch.dict(sys.modules, modules), patch("sys.platform", "win32"),
              patch("platform.machine", return_value="AMD64")):
            with patch("importlib.metadata.version", return_value="2.3.0"):
                self.assertIs(tf, inference_interpreter(bundled=True))
            with patch("importlib.metadata.version", return_value="3.0"):
                self.assertIs(tf, inference_interpreter(bundled=True))
            with patch("importlib.metadata.version", side_effect=ModuleNotFoundError):
                self.assertIs(tf, inference_interpreter(bundled=True))
