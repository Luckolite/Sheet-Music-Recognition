# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
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
