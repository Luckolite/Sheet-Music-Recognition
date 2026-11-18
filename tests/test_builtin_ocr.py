# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Synthetic scanned-tab regression for the installed OCR and Java decoder."""

import json
import platform
import sys
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
from PIL import Image

from sheet_music_recognition.ocr import LocalOcr, _byte_detector_input, tempo_numbers, rest_count_numbers
from sheet_music_recognition.reader import Interpreter, grayscale, java_executable, write_page


ROOT = Path(__file__).resolve().parents[1]


class BuiltinOcrTests(unittest.TestCase):
    @unittest.skipUnless(sys.platform == "win32" and platform.machine() == "AMD64",
                         "Windows x64 detector arithmetic")
    def test_detector_restores_callers_denormal_mode_even_after_failure(self):
        from sheet_music_recognition.ocr import _detector_inference

        def half_smallest_normal():
            value = np.array([0x00800000], dtype=np.uint32).view(np.float32)
            return (value * np.float32(.5)).view(np.uint32).item()

        previous = half_smallest_normal()
        self.assertEqual(0, _detector_inference(half_smallest_normal)())
        self.assertEqual(previous, half_smallest_normal())

        def fail():
            self.assertEqual(0, half_smallest_normal())
            raise RuntimeError("detector failed")

        with self.assertRaisesRegex(RuntimeError, "detector failed"):
            _detector_inference(fail)()
        self.assertEqual(previous, half_smallest_normal())

    @unittest.skipUnless(sys.platform == "win32" and platform.machine() == "AMD64",
                         "Windows x64 detector arithmetic")
    def test_detector_worker_mode_is_limited_to_detector_construction(self):
        from rapidocr.inference_engine.onnxruntime.main import OrtInferSession

        def caller_value():
            half_normal = np.array([0x00800000], dtype=np.uint32).view(np.float32) * np.float32(.5)
            return half_normal.view(np.uint32).item()

        seen = []
        original = OrtInferSession.__init__

        def inspect_session(session, cfg):
            if cfg.get("session") is None:
                options = session._init_sess_opts(cfg.engine_cfg)
                seen.append((options.get_session_config_entry("session.set_denormal_as_zero"),
                             options.intra_op_num_threads, caller_value()))
            original(session, cfg)

        before = caller_value()
        with patch.object(OrtInferSession, "__init__", inspect_session):
            LocalOcr(threads=1)
        self.assertEqual([("1", 1, 0)], seen)
        self.assertEqual(before, caller_value())

    def test_unsupported_detector_mode_uses_the_original_model_factory(self):
        from sheet_music_recognition.ocr import _detector_inference

        with patch.object(sys, "platform", "linux"):
            self.assertIs(_detector_inference(int, fallback=float), float)
            self.assertIs(_detector_inference(int), int)

    def test_detector_input_matches_original_float_bits_in_every_channel(self):
        from rapidocr.ch_ppocr_det.utils import DetPreProcess

        base = np.arange(1024, dtype=np.uint8).reshape(32, 32)
        image = np.stack((base, 255 - base, base.T), axis=-1)
        original = DetPreProcess(32, "min", [.485, .456, .406], [.229, .224, .225])
        prepare = _byte_detector_input(original)
        for pixels in (image, image[::2, 1::2]):
            tensor = prepare(pixels)
            self.assertTrue(tensor.flags.c_contiguous)
            np.testing.assert_array_equal(tensor.view(np.uint32),
                                          original(pixels).view(np.uint32))

    def test_white_page_preserves_native_annotations_and_defers_ocr_until_ink(self):
        owner = object.__new__(Interpreter)
        owner.ocr = None
        owner._threads = 2
        gray = np.full((65, 67), 255, dtype=np.uint8)
        supplied = {"tabWords": [{"text": "12"}]}
        with patch("sheet_music_recognition.ocr.LocalOcr") as create:
            result = owner._read_annotations(gray, supplied)
            self.assertEqual([], result["words"])
            self.assertEqual(supplied["tabWords"], result["tabWords"])
            create.assert_not_called()
            gray[-1, -1] = 254
            create.return_value.words.return_value = []
            owner._read_annotations(gray, None)
            create.assert_called_once_with(threads=2)
            create.return_value.words.assert_called_once_with(gray)

    def test_compound_meter_tempo_and_short_system_from_print(self):
        fixture = ROOT / 'tests/fixtures/compound-meter-score/page.png'
        with Image.open(fixture) as image:
            page = Interpreter().interpret(image, page_index=0)
        self.assertEqual([4, 3, 3], page['measureBeats'])
        self.assertEqual([(0, 4, 4), (1, 6, 8)], [
            (change['measureIndex'], change['numerator'], change['denominator'])
            for change in page['score']['meterChanges']])
        self.assertEqual([(0, 100, 1), (1, 108, 1.5)], [
            (mark['measureIndex'], mark['bpm'], mark['beatUnit'])
            for mark in page['score']['tempoChanges']])
        expected = [(0, 60, 1 / 3), (1 / 3, 62, 1 / 3), (2 / 3, 64, 1 / 3),
                    (1, 65, 1), (2, 67, 1.5), (3.5, 69, .5), (4, 67, .5),
                    (4.5, 65, .5), (5, 64, .5), (5.5, 62, 1.5),
                    (7, 64, 1), (8, 65, 1.5), (9.5, 67, .5)]
        self.assertEqual(len(expected), len(page['events']))
        for event, (onset, pitch, duration) in zip(page['events'], expected):
            self.assertEqual(pitch, event['midi'])
            self.assertAlmostEqual(onset, event['startBeat'], places=5)
            self.assertAlmostEqual(duration, event['durationBeats'], places=5)
        self.assertFalse(any(note['articulations'] & 8 for note in page['score']['notes']))

    def test_isolated_rest_count_is_offered_without_reading_a_verse_heading(self):
        # Original synthetic boxes; Java still requires a note-free heavy rest bar.
        words = [dict(text="2", left=.30, top=.20, right=.32, bottom=.22),
                 dict(text="Verse 2", left=.36, top=.19, right=.43, bottom=.21),
                 dict(text="34", left=.25, top=.20, right=.27, bottom=.22)]
        tokens = rest_count_numbers(words)
        self.assertEqual([2], [token["value"] for token in tokens])
        self.assertAlmostEqual(.30, tokens[0]["left"])

    def test_printed_tempo_line_bounds_the_digits_and_keeps_direction_anchor(self):
        # Original synthetic OCR geometry, with title and measure numbers nearby.
        words = [dict(text="J = 125", left=.15, top=.125, right=.21, bottom=.145),
                 dict(text="13", left=.06, top=.55, right=.08, bottom=.57),
                 dict(text="Op. 2023", left=.4, top=.04, right=.5, bottom=.06)]
        tokens = tempo_numbers(words)
        self.assertEqual(1, len(tokens))
        self.assertEqual(125, tokens[0]["value"])
        self.assertAlmostEqual(.15, tokens[0]["annotationLeft"])
        self.assertGreater(tokens[0]["left"], .18)
        self.assertAlmostEqual(.21, tokens[0]["right"])

    def test_scanned_pdf_staff_strokes_do_not_hide_a_twelve_fret(self):
        import pypdfium2 as pdfium

        document = pdfium.PdfDocument(ROOT / "tests/fixtures/synthetic-scanned-tab.pdf")
        page = document[0]
        bitmap = page.render(scale=2400 / page.get_width())
        try:
            gray = grayscale(bitmap.to_pil())
        finally:
            bitmap.close()
            page.close()
            document.close()
        words = LocalOcr().words(gray)
        self.assertEqual({"0", "12", "7", "3"}, {word["text"] for word in words})

    def test_scanned_frets_reach_the_decoder_without_supplied_annotations(self):
        # Newly drawn tab rules and frets, saved as pixels so rendering does not
        # vary across operating systems and font rasterizers.
        with Image.open(ROOT / "tests/fixtures/synthetic-scanned-tab.png") as image:
            gray = np.asarray(image.convert("L"), dtype=np.uint8)
        from rapidocr.main import TextClassifier, TextRecognizer
        from sheet_music_recognition import _ocr_engine

        with patch.object(_ocr_engine, "TextClassifier", wraps=TextClassifier) as classify, \
             patch.object(_ocr_engine, "TextRecognizer", wraps=TextRecognizer) as recognize:
            ocr = LocalOcr()
            self.assertEqual([], ocr.words(np.full((64, 96), 255, dtype=np.uint8)))
            classify.assert_not_called()
            recognize.assert_not_called()
            words = ocr.words(gray)
            self.assertEqual(words, ocr.words(gray))
            classify.assert_called_once()
            recognize.assert_called_once()
        self.assertEqual({"0", "12", "7", "3"}, {word["text"] for word in words})
        for fret, x in (("0", 240), ("12", 420), ("7", 590), ("3", 760)):
            word = next(word for word in words if word["text"] == fret)
            self.assertLess(abs((word["left"] + word["right"]) * 600 - x), 20)

        with tempfile.TemporaryDirectory() as folder:
            page = Path(folder) / "scan.page.gz"
            output = Path(folder) / "score.json"
            write_page(page, np.zeros_like(gray), gray, {"words": words})
            run = subprocess.run([java_executable(), "-jar", str(ROOT / "src/sheet_music_recognition/interpreter.jar"),
                                  str(page), str(output), "4", "4", "0"],
                                 capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)
            score = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual([64, 67, 52, 62], [event["midi"] for event in score["events"]])


if __name__ == "__main__":
    unittest.main()
