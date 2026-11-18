# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Runtime lookup for score inference and Java decoding."""
import os
import platform
import shutil
import sys
from pathlib import Path


def java_executable():
    if os.environ.get('JAVA_HOME'):
        path = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if path.is_file():
            return str(path)
    path = shutil.which('java')
    if not path:
        raise RuntimeError('Install Java 17 or newer and put java on PATH or set JAVA_HOME')
    return path


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
