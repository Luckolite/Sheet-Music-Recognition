# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Platform-specific LilyPond payload for Score Reader."""
from pathlib import Path
import sys
import tarfile
import zipfile

_WINDOWS = sys.platform == 'win32'
ARCHIVE = Path(__file__).with_name('runtime.zip' if _WINDOWS else 'runtime.tar.gz')


def unpack_runtime(archive, destination):
    # The wheel builder verifies the exact upstream archive before including it.
    if _WINDOWS:
        with zipfile.ZipFile(archive) as runtime:
            runtime.extractall(destination)
    else:
        with tarfile.open(archive, 'r:gz') as runtime:
            if hasattr(tarfile, 'data_filter'):
                runtime.extractall(destination, filter='data')
            else:
                runtime.extractall(destination)
    return destination / 'lilypond-2.26.0' / 'bin'
