# Python and Java API

[Install from source](../README.md#install-and-use) before running these examples.

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

For a PDF, the [CLI](../README.md#install-and-use) handles rendering, page order, and state across pages. Use `--pages 1,3` to select pages in reading order. `--meter 3/4`, `--bpm 90`, and `--key-fifths 2` set the starting meter, quarter-note tempo, and key fallback (sharps positive, flats negative).

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

The CLI document contains `schemaVersion`, `inputName`, `initialBpm`, `initialMeter`, `initialKeyFifths`, and `pages`.

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

Python normally runs local OCR. Use `--annotations file.json` to supply a JSON array with one object per selected page, or pass a single object as `annotations=` to `interpret()`.

```json
[
  {
    "words": [{"text": "mf", "left": 0.10, "top": 0.4, "right": 0.14, "bottom": 0.43}],
    "meters": [{"measureIndex": 0, "numerator": 3, "denominator": 4}]
  }
]
```

Boxes use normalized coordinates on the full page. Other optional lists are `tabWords`, `measureNumbers`, `tempoNumbers`, and `restCounts`. Number entries use `value` in place of `text` and accept an optional `annotationLeft` for the start of the printed direction. Tempo and rest counts still need supporting printed symbols. See the [decoder reference](decoder-reference.md#ocr) for Java OCR and meter matching.
