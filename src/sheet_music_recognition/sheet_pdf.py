# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Offline PDF engraving with the optional bundled LilyPond runtime."""
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory

from .musicxml import write_musicxml


def require_runtime():
    try:
        from score_reader_lilypond import ARCHIVE
    except ModuleNotFoundError as error:
        if error.name != 'score_reader_lilypond':
            raise
        raise RuntimeError('Sheet PDF export requires pip install "score-reader[sheet]"') from error
    return ARCHIVE


def write_sheet_pdf(document, path, *, musicxml=None, layout='original'):
    """Engrave the decoded score locally and replace the destination on success."""
    archive = require_runtime()
    path = Path(path).resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix='score-reader-lilypond-') as runtime, TemporaryDirectory(
            prefix='.score-reader-sheet-', dir=path.parent) as output:
        from score_reader_lilypond import unpack_runtime

        binary = unpack_runtime(archive, Path(runtime))
        work = Path(output)
        if musicxml is None:
            musicxml = work / 'score.musicxml'
            write_musicxml(document, musicxml)
        source = Path(musicxml).resolve()
        if layout != 'original':
            from score_reader_lilypond.layouts import prepare
            source = prepare(source, work / 'layout.musicxml', layout)
        lily_file = work / 'score.ly'
        if sys.platform == 'win32':
            converter = [binary / 'python.exe', binary / 'musicxml2ly.py']
            engraver = binary / 'lilypond.exe'
        else:
            converter = [binary / 'musicxml2ly']
            engraver = binary / 'lilypond'
        _run([*converter, '-o', lily_file, source], work)
        _run([engraver, '-o', work / 'score', lily_file], work)
        result = work / 'score.pdf'
        if not result.is_file():
            raise RuntimeError('LilyPond did not produce a sheet PDF')
        with result.open('rb') as stream:
            if stream.read(5) != b'%PDF-':
                raise RuntimeError('LilyPond returned an invalid sheet PDF')
        result.replace(path)


def _run(command, directory):
    completed = subprocess.run(command, cwd=directory, capture_output=True, text=True,
                               encoding='utf-8', errors='replace', check=False)
    if completed.returncode:
        details = (completed.stderr or completed.stdout).strip()
        raise RuntimeError(f'LilyPond failed with exit code {completed.returncode}: {details}')
