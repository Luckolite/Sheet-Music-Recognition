# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import ctypes
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from sheet_music_recognition.runtime import default_inference_threads, inference_interpreter


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

    def test_automatic_threads_follow_the_windows_process_affinity(self):
        class Lite:
            __module__ = "ai_edge_litert.interpreter"

        for mask, success, expected in ((0xfff, 1, 6), (0x7ff, 1, 2), (0xffff, 0, 2)):
            def affinity(handle, process_mask, system_mask):
                ctypes.cast(process_mask, ctypes.POINTER(ctypes.c_size_t))[0] = mask
                return success

            api = SimpleNamespace(kernel32=SimpleNamespace(GetProcessAffinityMask=affinity))
            with (self.subTest(mask=mask, success=success), patch("sys.platform", "win32"),
                  patch.object(ctypes, "windll", api, create=True)):
                self.assertEqual(expected, default_inference_threads(Lite))
                self.assertEqual(2, default_inference_threads(object))
        with patch("sys.platform", "linux"):
            self.assertEqual(2, default_inference_threads(Lite))
