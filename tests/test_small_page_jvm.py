# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from sheet_music_recognition import runtime


class SmallPageJvmTest(unittest.TestCase):
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
