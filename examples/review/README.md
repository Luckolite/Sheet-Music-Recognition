# Score review example

From the repository root, serve the files with Python:

```sh
python -m http.server --bind 127.0.0.1 8000
```

Open [the score review example](http://127.0.0.1:8000/examples/review/).
It loads the same original scale as the CLI's `--demo`. Select a marker to
inspect its pitch, onset and duration. Dashed boxes identify measures with
an inferred clef or duration fallback. Download the actual MusicXML, MIDI
or JSON, or listen to the synthesized preview.

To inspect your own score, open the JSON from the CLI or the
[document API](../../docs/integration.md#read-a-pdf), select a page, and load
the matching image. PDF input needs a raster of the corresponding full page.
The browser reads uploaded files locally.

To reuse the overlay, read [score schema v1](../../docs/schema/score-v1.json)
and map `x / width` and `y / height` onto the full displayed page. Keep the
same page crop and EXIF orientation as recognition. Measure boxes are
already normalized. Use `sourcePage` to find the matching input page and
`sourceNoteIndex` to look up the corresponding written note.

The original scale is distributed with the project. The displayed export
was rendered from the checked-in MusicXML using Verovio 6.3.0 with Bravura.
See the [font notice](../../training/fonts/BRAVURA-OFL.txt).
