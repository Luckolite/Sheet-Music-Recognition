# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import hashlib
import importlib.util
from io import BytesIO, StringIO
from pathlib import Path
import struct
import unittest
from unittest.mock import patch

import numpy as np


_path = Path(__file__).resolve().parents[1] / "optional/segmentation/directml_detector.py"
_spec = importlib.util.spec_from_file_location("directml_detector", _path)
detector = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(detector)


class DirectMlDetectorServiceTest(unittest.TestCase):
    def test_recognizer_reuses_session_and_writes_steps_and_scores(self):
        header = struct.pack(">II", *detector._RECOGNIZE)
        shared = bytearray(detector._MAPPING_BYTES)
        stream = BytesIO()
        expected = np.arange(2 * 504, dtype=np.float32).reshape(1, 2, 504)

        class Session:
            def run(self, names, inputs):
                assert inputs["x"].shape == (1, 3, 48, 320)
                return [expected]

        with (patch.object(Path, "read_bytes", return_value=b"recognizer"),
              patch.object(detector, "RECOGNIZER_SHA256", hashlib.sha256(b"recognizer").hexdigest()),
              patch.object(detector, "_open_model", return_value=Session()) as opener):
            detector._serve(b"model", shared, 1, BytesIO(header * 2), stream, Path("ocr.onnx"))
        opener.assert_called_once_with(b"recognizer", 1, (1, 320), 4)
        self.assertEqual(stream.getvalue(), b"KK")
        self.assertEqual(struct.unpack_from("<I", shared, detector._OUTPUT_OFFSET), (2,))
        actual = np.frombuffer(shared, "<f4", expected.size, detector._OUTPUT_OFFSET + 4)
        np.testing.assert_array_equal(actual, expected.ravel())

    def test_recognizer_failure_leaves_detector_available(self):
        detector_header = struct.pack(">II", 480, 128)
        recognizer_header = struct.pack(">II", *detector._RECOGNIZE)
        stream = BytesIO()

        class Session:
            def run(self, names, inputs):
                return [np.zeros((1, 1, 128, 480), dtype=np.float32)]

        with (patch.object(Path, "read_bytes", side_effect=OSError("offline")) as read,
              patch.object(detector, "_open_detector", return_value=Session()) as opener,
              patch.object(detector.sys, "stderr", StringIO())):
            detector._serve(b"model", bytearray(detector._MAPPING_BYTES), 1,
                            BytesIO(detector_header + recognizer_header * 2 + detector_header),
                            stream, Path("ocr.onnx"))
        read.assert_called_once()
        opener.assert_called_once()
        self.assertEqual(stream.getvalue(), b"KRRK")

    def test_reuses_shape_session_and_acknowledges_complete_output(self):
        width, height = 480, 128
        header = struct.pack(">II", width, height)
        shared = bytearray(detector._MAPPING_BYTES)
        expected = np.arange(width * height, dtype=np.float32).reshape(1, 1, height, width)
        stream = BytesIO()

        class Session:
            def run(self, names, inputs):
                assert inputs["x"].shape == (1, 3, height, width)
                return [expected]

        with patch.object(detector, "_open_detector", return_value=Session()) as opener:
            detector._serve(b"model", shared, 1, BytesIO(header * 2), stream)
        opener.assert_called_once_with(b"model", 1, width, height)
        self.assertEqual(stream.getvalue(), b"KK")
        actual = np.frombuffer(shared, "<f4", width * height, detector._OUTPUT_OFFSET)
        np.testing.assert_array_equal(actual, expected.ravel())

    def test_rejects_invalid_request_before_inference(self):
        for payload, expected in ((b"x", EOFError), (struct.pack(">II", 1, 1), ValueError)):
            stream = BytesIO()
            with self.subTest(payload=payload), patch.object(detector, "_open_detector") as opener:
                with self.assertRaises(expected):
                    detector._serve(b"model", bytearray(), 1, BytesIO(payload), stream)
                opener.assert_not_called()
                self.assertEqual(stream.getvalue(), b"")

    def test_rejects_bad_output_without_acknowledgement(self):
        stream = BytesIO()

        class Session:
            def run(self, names, inputs):
                return [np.zeros((1, 1, 128, 480), dtype=np.float64)]

        with patch.object(detector, "_open_detector", return_value=Session()):
            with self.assertRaisesRegex(ValueError, "output tensor"):
                detector._serve(b"model", bytearray(detector._MAPPING_BYTES), 1,
                                BytesIO(struct.pack(">II", 480, 128)), stream)
        self.assertEqual(stream.getvalue(), b"")
