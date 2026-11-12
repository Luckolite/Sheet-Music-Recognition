# Python and Java API

[Install the interpreter](../README.md#install-and-use) before running these examples.

## Python

Use `Interpreter.interpret()` to read one image. Pass a Pillow image and reuse one interpreter per worker.

```python
from PIL import Image
from sheet_music_recognition import Interpreter, write_musicxml

reader = Interpreter()
with Image.open("examples/scale.png") as image:
    page = reader.interpret(image, meter=(4, 4))

write_musicxml({"pages": [page]}, "score.musicxml")
```

### Read a PDF

Install the `pdf` extra and use the same interpreter to recognize an entire
document. The method also accepts an image file path.

```python
from sheet_music_recognition import Interpreter, write_musicxml

reader = Interpreter()
score = reader.interpret_document(
    "score.pdf",
    pages=[1, 3],
    progress=lambda done, total, page: print(f"Read page {page} ({done}/{total})"),
)
write_musicxml(score, "score.musicxml")
```

Omit `pages` to read the whole document. Page numbers are one-based, unique,
and increasing. The result keeps the physical page numbers, inherits key and
meter from previously read pages, and resolves ties across adjacent read pages.
Set the initial `meter`, quarter-note `bpm`, and `key_fifths` as needed. The
progress callback runs after each selected page, before final document assembly.
Reuse one interpreter per worker.

### Export saved JSON

Export again without running recognition:

```python
import json

from sheet_music_recognition import write_mp3
from sheet_music_recognition.midi import write_midi

with open("score.json", encoding="utf-8") as source:
    score = json.load(source)

bpm = score.get("initialBpm", 120)
write_midi(score, "preview.mid", bpm=bpm)
write_mp3(score, "preview.mp3", bpm=bpm)
```

MP3 uses a simple synthesized tone and the same timing as MIDI. Install the `audio` extra or provide FFmpeg with `libmp3lame`. Use `ffmpeg=` and `bitrate=` in Python, or `--ffmpeg` and `--mp3-bitrate` on the CLI.

## Java

The standalone JAR is built at `src/sheet_music_recognition/interpreter.jar`. Add it to your classpath. Supply a row-major segmentation label map and grayscale image, each as a flat `byte[]` of length `width * height`:

```java
import io.github.luckolite.interpreter.ScorePageInterpretation;
import io.github.luckolite.interpreter.SheetInterpreter;

ScorePageInterpretation page = SheetInterpreter.analyze(labels, gray, width, height);
```

Java decodes the supplied pixels; the Python reader also runs the model and OCR. Java callers can supply `SheetInterpreter.Annotations` for OCR results. See the [model card](../models/MODEL_CARD.md) for label IDs and the [decoder reference](decoder-reference.md) for runtime limits and advanced APIs.

## JSON output

The CLI and `interpret_document()` return the same document shape. Use the
[v1 JSON Schema](schema/score-v1.json) to validate the envelope and overlay
fields. Additional decoder fields may appear within v1. See the
[browser review example](../examples/review/README.md) for a coordinate overlay.

| Page field | Use |
| --- | --- |
| `sourcePage` | One-based source page number in CLI output. |
| `width`, `height`, `modelSha256` | Analysis image dimensions and the model used. |
| `score` | Written notes, rests, measure boxes, key, meter and tempo changes, and any retained directions. |
| `events` | MIDI pitch, start and duration in quarter-note beats, staff, pixel position, tie flags, and `sourceNoteIndex` (zero-based on the page). |
| `measureBeats`, `totalBeats`, `warnings` | Measure and page lengths in quarter-note beats, plus diagnostics. |

Measure indices and event times are local to each page. Measure boxes use normalized 0–1 coordinates. Detected `bpm` is always in quarter notes per minute; `beatUnit` records the printed pulse length. For example, eighth note = 160 gives `bpm: 80` and `beatUnit: 0.5`.

`clefInferred` flags a fallback clef; `durationFallback` flags an unresolved duration. These flags are not confidence scores. See [playback behavior](expressive-performance.md) for how written notation becomes a preview.

## Supply your own annotations

Python normally runs local OCR. Pass one object per selected page to
`interpret_document(annotations=...)` or supply a JSON array to the CLI with
`--annotations file.json`. Pass a single object to `interpret(annotations=...)`.

```json
[
  {
    "words": [{"text": "mf", "left": 0.10, "top": 0.4, "right": 0.14, "bottom": 0.43}],
    "meters": [{"measureIndex": 0, "numerator": 3, "denominator": 4}]
  }
]
```

Boxes use normalized coordinates on the full page. Other optional lists are `tabWords`, `measureNumbers`, `tempoNumbers`, and `restCounts`. Number entries use `value` in place of `text` and accept an optional `annotationLeft` for the start of the printed direction. Tempo and rest counts still need supporting printed symbols. See the [decoder reference](decoder-reference.md#ocr) for Java OCR and meter matching.
