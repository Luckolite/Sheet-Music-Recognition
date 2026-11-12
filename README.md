# Sheet-Music-Recognition — Optical Music Recognition

Convert sheet-music images and scanned PDFs to **MusicXML, MIDI, JSON and MP3**
with offline optical music recognition (OMR). Sheet-Music-Recognition includes downloadable
model weights, a Java decoder and a Python API and command-line tool, available
as **`score-reader`** (PyPI package and command) and **`sheet_music_recognition`** (Python import).

[![Build and test](https://github.com/Luckolite/Sheet-Music-Recognition/actions/workflows/ci.yml/badge.svg)](https://github.com/Luckolite/Sheet-Music-Recognition/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/LICENSE)

[Quick start](#install-and-use) · [Review demo](https://github.com/Luckolite/Sheet-Music-Recognition/tree/main/examples/review) · [Benchmark](https://github.com/Luckolite/Sheet-Music-Recognition/tree/main/benchmarks/heldout) · [Documentation](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/README.md) · [Python / Java API](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/integration.md) · [Downloads](https://github.com/Luckolite/Sheet-Music-Recognition/releases)

**Code and weights are Apache-2.0 licensed, including commercial and closed-source use.**
You do not need to publish your own code. Follow the [license and notice requirements](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/licensing.md).

## Install & Use

### Android Demo

Import a PDF or image, and generate a play-along track. Available on [Google Play](https://play.google.com/store/apps/details?id=com.musicsheets) · [Sample PDF](https://raw.githubusercontent.com/Luckolite/Sheet-Music-Recognition/main/examples/scale.pdf)

### Run the demo

Requires **Python 3.10 or 3.11** and **Java 17+** on your PATH (or via `JAVA_HOME`).

```sh
python -m pip install "score-reader[pdf,audio]"
score-reader --demo -o scale.json --musicxml scale.musicxml --midi scale.mid --mp3 scale.mp3
```

| Input image | Recognized score |
| --- | --- |
| ![Original two-measure scale](https://raw.githubusercontent.com/Luckolite/Sheet-Music-Recognition/main/examples/scale.png) | ![Engraved MusicXML from the conversion](https://raw.githubusercontent.com/Luckolite/Sheet-Music-Recognition/main/examples/review/scale-rendered.png) |

[Listen to the preview](https://raw.githubusercontent.com/Luckolite/Sheet-Music-Recognition/main/examples/review/scale.mp3) · [Inspect detected notes](https://github.com/Luckolite/Sheet-Music-Recognition/tree/main/examples/review) · [Download MusicXML](https://raw.githubusercontent.com/Luckolite/Sheet-Music-Recognition/main/examples/review/scale.musicxml)

### Read a score

```sh
# Image to MusicXML and MIDI
score-reader score.png -o score.json --musicxml score.musicxml --midi score.mid

# PDF to an audio preview
score-reader score.pdf -o score.json --mp3 score.mp3
```

Processing runs locally. Every conversion writes JSON. Add `--musicxml`,
`--midi`, or `--mp3` for the exports you need. MP3 uses a synthesized tone
and requires FFmpeg, which the `audio` extra supplies.

| Option | What it sets |
| --- | --- |
| `--pages 1,3` | PDF pages to read |
| `--meter 3/4` | Initial meter (default 4/4) |
| `--bpm 90` | Initial quarter-note tempo (default 120 BPM) |
| `--key-fifths 2` | Starting key signature with two sharps |

Run `score-reader --help` for all options. [API and output details](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/integration.md) · [Standalone JAR and model downloads](https://github.com/Luckolite/Sheet-Music-Recognition/releases)

## What it supports

- **Scores.** Printed staff notation for piano, violin, and ensembles, including independently barred staves.
- **Notation.** Notes, chords, rests, clefs, accidentals, ties, slurs, grace notes, and dynamics. Reads repeats and endings, plus changes in key, meter, and tempo.
- **Playback.** MIDI and synthesized MP3 previews can follow recognized ritardandos, fermatas, tremolos, and piano pedal brackets. [Playback details](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/expressive-performance.md)
- **Guitar tab.** Six- and seven-string tab with tuning headers, written rhythms including triplets, and effects such as hammer-ons, pull-offs, slides, bends, and vibrato. When rhythm is missing, the reader estimates it. [Tab details](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/guitar-tabs.md) · [Guitar effects](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/tab-gestures.md)
- **Exports.** MusicXML reconstructs an editable concert-pitch score and leaves out the original page layout and tab placement. JSON includes note events and page geometry. [Export formats](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/integration.md)

## Integration and model

Use `Interpreter` and `write_musicxml` from Python, or `SheetInterpreter.analyze()`
from Java. See [API examples and output format](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/integration.md).
An optional [native Java decoding service](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/native-decoder.md) supports
bounded, source-matched geometry and analysis requests from local workers.
The Python reader runs bundled-model OCR automatically on images and scanned PDFs.
The separate [shared Java OCR pipeline](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/portable-ocr.md) and optional ONNX
binding remain available for cross-platform evaluation. The optional
[desktop ONNX segmentation adapter](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/portable-segmentation.md) uses the pinned
v3 conversion without changing the default Python model.

The bundled v4 model comes from our own synthetic training lineage, without pretrained
weights or commercial score scans. See the [model card](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/models/MODEL_CARD.md), [evaluation](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/models/evaluation.json),
[training guide](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/training/README.md) and [source origins](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/source-provenance.md).

See the [contributor guide](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/CONTRIBUTING.md) for source builds, tests, and bug reports.
