# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Runtime lookup for score inference and Java decoding."""
import os
import platform
import shutil
import sys
from pathlib import Path


_SHORT_DECODER_BUILDS = (
    ('IMPLEMENTOR="JetBrains s.r.o."', 'JAVA_RUNTIME_VERSION="21.0.10+-14961533-b1163.108"'),
    ('IMPLEMENTOR="Eclipse Adoptium"', 'JAVA_RUNTIME_VERSION="17.0.20.1+1"'),
    ('IMPLEMENTOR="Oracle Corporation"', 'JAVA_VERSION="19.0.1"', 'SOURCE=".:git:aa986c51a096"'),
)


def java_executable():
    if os.environ.get('JAVA_HOME'):
        path = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if path.is_file():
            return str(path)
    path = shutil.which('java')
    if not path:
        raise RuntimeError('Install Java 17 or newer and put java on PATH or set JAVA_HOME')
    return path


def small_page_jvm_args(java, pixels):
    """Avoid higher-tier compilation for short runs on qualified HotSpot builds."""
    if sys.platform != "win32" or not 0 < pixels <= 500_000:
        return ()
    if any(os.environ.get(name) for name in ("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")):
        return ()
    try:
        release = set((Path(java).parent.parent / "release").read_text(encoding="utf-8").splitlines())
    except (OSError, UnicodeError):
        return ()
    if any(release.issuperset(build) for build in _SHORT_DECODER_BUILDS):
        return ("-XX:TieredStopAtLevel=1",)
    return ()


def inference_interpreter(*, bundled):
    """Use the qualified Windows CPU runtime for the bundled model."""
    if bundled and sys.platform == "win32" and platform.machine() == "AMD64":
        from importlib.metadata import version
        try:
            if version("ai-edge-litert") == "2.3.0":
                from ai_edge_litert.interpreter import Interpreter
                return Interpreter
        except (ImportError, OSError):
            # Source installs and older workers can still use their TensorFlow runtime.
            pass

    try:
        import tensorflow as tf
    except ImportError as error:
        raise RuntimeError("Recognition dependencies are missing. Run: pip install --upgrade score-reader") from error
    return tf.lite.Interpreter
