# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Run the CPU benchmark or rescore an existing directory of raw MusicXML."""
import argparse
from collections import Counter
from fractions import Fraction
import json
from pathlib import Path
import platform
from time import perf_counter
import xml.etree.ElementTree as xml
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
STEPS = dict(C=0, D=2, E=4, F=5, G=7, A=9, B=11)
METRICS = {
    "pitch": ("part", "staff", "measure", "onset", "pitch"),
    "rhythm": ("part", "staff", "measure", "onset", "duration"),
    "exact": ("part", "staff", "measure", "onset", "pitch", "duration"),
}


def written_notes(path):
    root = xml.parse(path).getroot()
    if root.tag != "score-partwise":
        raise ValueError(f"Expected uncompressed, partwise MusicXML: {path}")
    for part_number, part in enumerate(root.findall("part"), 1):
        divisions = 1
        for measure_number, measure in enumerate(part.findall("measure"), 1):
            cursor = last_onset = Fraction(0)
            for element in measure:
                if element.tag == "attributes":
                    divisions = int(element.findtext("divisions", str(divisions)))
                    continue
                ticks = element.findtext("duration")
                if ticks is None:
                    continue
                duration = Fraction(int(ticks), divisions)
                if element.tag == "backup":
                    cursor -= duration
                elif element.tag == "forward":
                    cursor += duration
                elif element.tag == "note":
                    if element.find("chord") is None:
                        last_onset = cursor
                        cursor += duration
                    pitch = element.find("pitch")
                    if pitch is None:
                        continue
                    yield {
                        "part": part_number,
                        "staff": int(element.findtext("staff", "1")),
                        "measure": measure_number,
                        "onset": last_onset,
                        "pitch": (int(pitch.findtext("octave")) + 1) * 12
                                 + STEPS[pitch.findtext("step")]
                                 + Fraction(pitch.findtext("alter", "0")),
                        "duration": duration,
                    }


def compare(reference, prediction):
    result = {"referenceNotes": len(reference), "predictedNotes": len(prediction)}
    for metric, fields in METRICS.items():
        expected = Counter(tuple(note[key] for key in fields) for note in reference)
        observed = Counter(tuple(note[key] for key in fields) for note in prediction)
        result[metric] = {"matched": sum((expected & observed).values())}
    exact = result["exact"]["matched"]
    result["exact"].update(missed=len(reference) - exact, extra=len(prediction) - exact)
    return result


def summarize(rows):
    reference = sum(row["referenceNotes"] for row in rows)
    prediction = sum(row["predictedNotes"] for row in rows)
    aggregate = {"referenceNotes": reference, "predictedNotes": prediction}
    for metric in METRICS:
        matches = sum(row[metric]["matched"] for row in rows)
        aggregate[metric] = {
            "matched": matches,
            "precision": matches / prediction if prediction else 0.0,
            "recall": matches / reference if reference else 0.0,
            "f1": 2 * matches / (reference + prediction) if reference + prediction else 0.0,
        }
    matches = aggregate["exact"]["matched"]
    aggregate["exact"].update(missed=reference - matches, extra=prediction - matches)
    return aggregate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Directory for raw outputs and metrics")
    parser.add_argument("--rescore", action="store_true", help="Score existing raw MusicXML without running OMR")
    parser.add_argument("--threads", type=int, default=2)
    args = parser.parse_args()
    metadata = {}
    if not args.rescore:
        from sheet_music_recognition import Interpreter, __version__, write_musicxml

        args.output.mkdir(parents=True, exist_ok=False)
        engine = Interpreter(threads=args.threads)
        with ZipFile(engine.jar) as jar:
            decoder_sha = jar.read(
                "io/github/luckolite/interpreter/native-decoder-sha256.txt",
            ).decode("ascii").strip()
        metadata = {
            "packageVersion": __version__,
            "modelSha256": engine.model_sha256,
            "decoderSourceSha256": decoder_sha,
            "system": platform.platform(),
            "processor": platform.processor(),
            "threads": args.threads,
        }
    rows = {}
    for source in sorted(HERE.glob("*.png")):
        name = source.stem
        result_file = args.output / f"{name}.musicxml"
        seconds = None
        if not args.rescore:
            started = perf_counter()
            document = engine.interpret_document(source)
            seconds = perf_counter() - started
            (args.output / f"{name}.json").write_text(
                json.dumps(document, indent=2, allow_nan=False) + "\n", encoding="utf-8",
            )
            write_musicxml(document, result_file)
        row = compare(list(written_notes(source.with_suffix(".musicxml"))),
                      list(written_notes(result_file)))
        if seconds is not None:
            row["recognitionSeconds"] = round(seconds, 3)
        rows[name] = row
    if not rows:
        raise ValueError("No benchmark source pages found")
    summary = summarize(list(rows.values()))
    if not args.rescore:
        summary["secondsPerPage"] = round(
            sum(row["recognitionSeconds"] for row in rows.values()) / len(rows), 3,
        )
    result = json.dumps({"run": metadata, "pages": rows, "microAverage": summary}, indent=2) + "\n"
    if not args.rescore:
        (args.output / "results.json").write_text(result, encoding="utf-8")
    print(result, end="")


if __name__ == "__main__":
    main()
