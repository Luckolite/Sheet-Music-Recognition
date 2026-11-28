# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Local image/PDF to JSON, MIDI, MP3, MusicXML or engraved PDF."""
import argparse
import json
from pathlib import Path
import sys
import logging
from importlib.util import find_spec
from contextlib import contextmanager
from .document import interpret_document
from .reader import Interpreter
from .midi import write_midi
from .musicxml import write_musicxml


@contextmanager
def _ocr_diagnostics():
    # Redirected progress may land on a slow sink. Keep model warnings and
    # errors, and leave interactive diagnostics at the library's level.
    if sys.stderr.isatty():
        yield
        return
    logger = logging.getLogger("RapidOCR")

    def warnings_only(record):
        return record.levelno >= logging.WARNING

    logger.addFilter(warnings_only)
    try:
        yield
    finally:
        logger.removeFilter(warnings_only)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    has_sheet = find_spec("score_reader_lilypond") is not None
    parser.add_argument("input", type=Path, nargs="?", help="Image or PDF to recognize, or MusicXML to engrave")
    parser.add_argument("--demo", action="store_true", help="Recognize the bundled sample score")
    parser.add_argument("--output", "-o", required=not has_sheet, type=Path)
    parser.add_argument("--midi", type=Path)
    parser.add_argument("--mp3", type=Path, help="Optional synthesized audio preview")
    parser.add_argument("--ffmpeg", type=Path, help="FFmpeg executable for MP3 export")
    parser.add_argument("--mp3-bitrate", type=int, default=192, choices=(64, 96, 128, 160, 192, 256, 320))
    parser.add_argument("--musicxml", type=Path, help="Optional concert-pitch MusicXML export")
    parser.add_argument("--sheet-pdf", type=Path, help="Optional engraved PDF (requires the sheet extra)")
    if has_sheet and find_spec("score_reader_lilypond.layouts") is not None:
        from score_reader_lilypond.layouts import LAYOUTS
        parser.add_argument("--layout", choices=LAYOUTS, help="Sheet PDF layout (default: original)")
    parser.add_argument("--meter", default="4/4", help="Initial meter; read by the caller (default: 4/4)")
    parser.add_argument("--bpm", type=float, default=120, help="Initial quarter-note BPM for exports")
    parser.add_argument("--key-fifths", type=int, default=0, help="Fallback key: sharps positive, flats negative")
    parser.add_argument("--instrument", choices=("violin",),
                        help="Use standard violin tuning to resolve printed open-string harmonics")
    parser.add_argument("--width", type=int, default=2048)
    parser.add_argument("--threads", type=int, default=2, help="Recognition worker threads (1..32, default: 2)")
    parser.add_argument("--model", type=Path, help="Custom TFLite model with the documented tensor contract")
    parser.add_argument("--pages", help="One-based comma-separated PDF pages, in reading order")
    parser.add_argument("--annotations", type=Path, help="JSON list of annotation objects, one per selected page")
    args = parser.parse_args()
    layout = getattr(args, "layout", None)
    if layout and not args.sheet_pdf:
        parser.error("--layout requires --sheet-pdf")
    if args.output is None and not args.sheet_pdf:
        parser.error("--output is required unless exporting a sheet PDF")
    if args.demo == (args.input is not None):
        parser.error("Provide an input file or use --demo")
    if args.demo:
        args.input = Path(__file__).parent / "data" / "scale.png"
    try:
        meter = tuple(int(n) for n in args.meter.split("/"))
        if len(meter) != 2:
            raise ValueError("Use --meter numerator/denominator")
        if not 15 <= args.bpm <= 400:
            raise ValueError("BPM must be 15..400")
        annotations = json.loads(args.annotations.read_text(encoding="utf-8")) if args.annotations else None
        if annotations is not None and not isinstance(annotations, list):
            raise ValueError("Annotations must be a JSON list, one object per selected page")
        outputs = [p for p in (args.output, args.midi, args.mp3, args.musicxml, args.sheet_pdf) if p]
        if len({p.resolve() for p in outputs}) != len(outputs):
            raise ValueError("Output files must have different paths")
        for output in outputs:
            if output and output.resolve() == args.input.resolve():
                raise ValueError("Output must not overwrite the input score")
        if args.ffmpeg and not args.mp3:
            raise ValueError("--ffmpeg requires --mp3")
        if args.mp3:
            from .audio import find_ffmpeg
            find_ffmpeg(args.ffmpeg)  # Fail before expensive recognition if unavailable.
        if args.sheet_pdf:
            from .sheet_pdf import require_runtime, write_sheet_pdf
            require_runtime()
            if args.input.suffix.lower() in (".xml", ".musicxml", ".mxl"):
                if any((args.output, args.midi, args.mp3, args.musicxml, args.pages, args.annotations, args.model)):
                    raise ValueError("MusicXML input supports sheet PDF output only")
                write_sheet_pdf(None, args.sheet_pdf, musicxml=args.input, layout=layout or "original")
                return
        pages = [int(number) for number in args.pages.split(",")] if args.pages else None
        # Initialize the bundled model alongside OCR preparation on the first page.
        use_deferred_model = args.model is None and annotations is None
        engine = Interpreter(args.model, args.threads, _defer_model=use_deferred_model)

        def progress(_completed, _total, source_page):
            print(f"Read page {source_page}", file=sys.stderr)

        with _ocr_diagnostics():
            document = interpret_document(
                engine, args.input, pages=pages, meter=meter, bpm=args.bpm,
                key_fifths=args.key_fifths, width=args.width,
                annotations=annotations, progress=progress, instrument=args.instrument,
                _release_after_prediction=use_deferred_model,
            )
        results = document["pages"]
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(document, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        if args.midi:
            args.midi.parent.mkdir(parents=True, exist_ok=True)
            write_midi(document, args.midi, args.bpm)
        if args.musicxml:
            args.musicxml.parent.mkdir(parents=True, exist_ok=True)
            write_musicxml(document, args.musicxml)
        if args.sheet_pdf:
            write_sheet_pdf(document, args.sheet_pdf, musicxml=args.musicxml, layout=layout or "original")
        if args.mp3:
            from .audio import write_mp3
            write_mp3(document, args.mp3, args.bpm, ffmpeg=args.ffmpeg, bitrate=args.mp3_bitrate)
        print(f"Wrote {len(results)} page(s), {sum(len(p['events']) for p in results)} detected notes to {args.output or args.sheet_pdf}", file=sys.stderr)
    except (ValueError, RuntimeError, OSError, KeyError, TypeError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
