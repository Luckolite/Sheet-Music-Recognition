# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Local image/PDF to JSON, with optional MIDI, MP3 and MusicXML exports."""
import argparse
import json
from pathlib import Path
import sys
from .document import interpret_document
from .reader import Interpreter
from .midi import write_midi
from .musicxml import write_musicxml


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, nargs="?", help="Image or PDF to recognize")
    parser.add_argument("--demo", action="store_true", help="Recognize the bundled sample score")
    parser.add_argument("--output", "-o", required=True, type=Path)
    parser.add_argument("--midi", type=Path)
    parser.add_argument("--mp3", type=Path, help="Optional synthesized audio preview")
    parser.add_argument("--ffmpeg", type=Path, help="FFmpeg executable for MP3 export")
    parser.add_argument("--mp3-bitrate", type=int, default=192, choices=(64, 96, 128, 160, 192, 256, 320))
    parser.add_argument("--musicxml", type=Path, help="Optional concert-pitch MusicXML export")
    parser.add_argument("--meter", default="4/4", help="Initial meter; read by the caller (default: 4/4)")
    parser.add_argument("--bpm", type=float, default=120, help="Initial quarter-note BPM for exports")
    parser.add_argument("--key-fifths", type=int, default=0, help="Fallback key: sharps positive, flats negative")
    parser.add_argument("--width", type=int, default=2048)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--model", type=Path, help="Custom TFLite model with the documented tensor contract")
    parser.add_argument("--pages", help="One-based comma-separated PDF pages, in reading order")
    parser.add_argument("--annotations", type=Path, help="JSON list of annotation objects, one per selected page")
    args = parser.parse_args()
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
        outputs = [p for p in (args.output, args.midi, args.mp3, args.musicxml) if p]
        if len({p.resolve() for p in outputs}) != len(outputs):
            raise ValueError("JSON, MIDI, MP3 and MusicXML outputs must be different files")
        for output in outputs:
            if output and output.resolve() == args.input.resolve():
                raise ValueError("Output must not overwrite the input score")
        if args.ffmpeg and not args.mp3:
            raise ValueError("--ffmpeg requires --mp3")
        if args.mp3:
            from .audio import find_ffmpeg
            find_ffmpeg(args.ffmpeg)  # Fail before expensive recognition if unavailable.
        pages = [int(number) for number in args.pages.split(",")] if args.pages else None
        engine = Interpreter(args.model, args.threads)

        def progress(_completed, _total, source_page):
            print(f"Read page {source_page}", file=sys.stderr)

        document = interpret_document(
            engine, args.input, pages=pages, meter=meter, bpm=args.bpm,
            key_fifths=args.key_fifths, width=args.width,
            annotations=annotations, progress=progress,
        )
        results = document["pages"]
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(document, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        if args.midi:
            args.midi.parent.mkdir(parents=True, exist_ok=True)
            write_midi(document, args.midi, args.bpm)
        if args.musicxml:
            args.musicxml.parent.mkdir(parents=True, exist_ok=True)
            write_musicxml(document, args.musicxml)
        if args.mp3:
            from .audio import write_mp3
            write_mp3(document, args.mp3, args.bpm, ffmpeg=args.ffmpeg, bitrate=args.mp3_bitrate)
        print(f"Wrote {len(results)} page(s), {sum(len(p['events']) for p in results)} detected notes to {args.output}", file=sys.stderr)
    except (ValueError, RuntimeError, OSError, KeyError, TypeError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
