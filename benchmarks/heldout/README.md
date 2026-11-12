# Held-out CPU benchmark

Four newly composed, redistributable pages exercise mixed rhythms, accidentals
on an angled page, faint ledger lines, and two-staff piano chords. They were
authored after the bundled model and decoder were fixed for the baseline.
The [generator](generate.py) records the composition and rendering procedure.
The reference MusicXML is the source of each input image.

Install the interpreter, then run from the repository root:

```sh
python benchmarks/heldout/run.py build/heldout
```

Use a new output directory. The runner saves raw recognition JSON, MusicXML
and timing. It reuses one CPU interpreter with two inference threads by
default. Recognition time is wall-clock time on CPU, including raster prep,
inference, OCR and Java decoding. It excludes interpreter construction and
export, and the first page includes lazy OCR initialization.

Rescore the checked-in [baseline outputs](baseline) or substitute another
engine's uncompressed partwise MusicXML with the same filenames:

```sh
python benchmarks/heldout/run.py benchmarks/heldout/baseline --rescore
```

The scorer compares multisets of written pitched notes in the exported
MusicXML using exact rational durations. Pitch matches use part, staff,
measure, onset and pitch. Rhythm matches use part, staff, measure, onset and
duration. Exact matches require both pitch and duration. A missed reference
and an extra prediction can represent one substitution. Precision, recall and
F1 aggregate note counts across pages.

The [baseline](baseline/results.json) matched 27 of 49 reference notes
exactly, with 22 misses and 37 extra predictions under the exact-match
definition. Pitch F1 was 51.3%, rhythm F1 was 61.9%, and exact F1 was 47.8%.
Recognition averaged 21.8 seconds per page on the recorded Windows CPU.

This is a small synthetic holdout. The metrics cover written pitched notes
on these pages. The corpus does not measure handwriting, real scan defects,
ornaments, lyrics, unpitched notation or playback accuracy.

The exercises and ground truth are original and distributed under the
[project license](../../LICENSE). Rendering uses Verovio 6.3.0, resvg-py
0.3.0 and the Bravura glyphs ([font notice](../../training/fonts/BRAVURA-OFL.txt)).
