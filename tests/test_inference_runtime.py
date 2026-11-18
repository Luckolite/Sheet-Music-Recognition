# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from sheet_music_recognition.runtime import inference_interpreter


class InferenceRuntimeTest(unittest.TestCase):
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
