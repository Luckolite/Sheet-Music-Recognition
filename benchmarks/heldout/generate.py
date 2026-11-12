# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Render newly composed exercises. Run with Verovio 6.3.0 and resvg-py 0.3.0."""
import io
from pathlib import Path
import xml.etree.ElementTree as xml

from PIL import Image, ImageEnhance, ImageFilter
import resvg_py
import verovio

HERE = Path(__file__).resolve().parent
EXERCISES = {
    "clean-melody": (
        ("E4:2 G4:2 C5:4 B4:2 A4:2 G4:4", "F4:4 A4:4 D5:2 C5:2 G4:4"),
    ),
    "angled-accidentals": (
        ("G4:4 Bb4:4 C5:2 A4:2 F#4:4", "G4:2 A4:2 D5:4 C5:4 A4:4"),
    ),
    "faint-ledgers": (
        ("C4:4 E4:2 G4:2 C5:4 E5:4", "G5:2 A5:2 F5:4 D5:4 C4:4"),
    ),
    "piano-chords": (
        ("E4+G4:4 D4+F4:4 C4+E4:8", "F4+A4:4 E4+G4:4 D4+F4:4 C4+E4:4"),
        ("C3:8 G2:8", "F2:8 C3:8"),
    ),
}
DURATION_TYPES = {2: "eighth", 4: "quarter", 8: "half"}


def add(parent, tag, value=None, **attributes):
    node = xml.SubElement(parent, tag, attributes)
    if value is not None:
        node.text = str(value)
    return node


def reference(name, staves):
    score = xml.Element("score-partwise", version="4.0")
    add(add(score, "work"), "work-title", name)
    add(add(add(score, "part-list"), "score-part", id="P1"), "part-name", "Score")
    part = add(score, "part", id="P1")
    for number, voices in enumerate(zip(*staves), 1):
        measure = add(part, "measure", number=str(number))
        if number == 1:
            attributes = add(measure, "attributes")
            add(attributes, "divisions", 4)
            add(add(attributes, "key"), "fifths", 0)
            time = add(attributes, "time")
            add(time, "beats", 4)
            add(time, "beat-type", 4)
            add(attributes, "staves", len(staves))
            for index, (sign, line) in enumerate((("G", 2), ("F", 4))[:len(staves)], 1):
                clef = add(attributes, "clef", number=str(index))
                add(clef, "sign", sign)
                add(clef, "line", line)
        for staff, voice in enumerate(voices, 1):
            if staff > 1:
                add(add(measure, "backup"), "duration", 16)
            for chord in voice.split():
                pitches, beats = chord.split(":")
                for index, spelling in enumerate(pitches.split("+")):
                    note = add(measure, "note")
                    if index:
                        add(note, "chord")
                    pitch = add(note, "pitch")
                    add(pitch, "step", spelling[0])
                    accidental = spelling[1:-1]
                    if accidental:
                        add(pitch, "alter", {"#": 1, "b": -1}[accidental])
                    add(pitch, "octave", spelling[-1])
                    add(note, "duration", beats)
                    add(note, "voice", staff)
                    add(note, "type", DURATION_TYPES[int(beats)])
                    if accidental:
                        add(note, "accidental", {"#": "sharp", "b": "flat"}[accidental])
                    add(note, "staff", staff)
    xml.indent(score)
    return xml.tostring(score, encoding="unicode", xml_declaration=True) + "\n"


def main():
    for name, staves in EXERCISES.items():
        source = reference(name, staves)
        (HERE / f"{name}.musicxml").write_text(source, encoding="utf-8")
        renderer = verovio.toolkit()
        renderer.setOptions({
            "font": "Bravura", "pageWidth": 1360, "scale": 140,
            "adjustPageHeight": True, "header": "none", "footer": "none",
            "breaks": "none",
        })
        if not renderer.loadData(source) or renderer.getPageCount() != 1:
            raise RuntimeError(f"Expected one engraved page for {name}")
        raster = resvg_py.svg_to_bytes(svg_string=renderer.renderToSVG(1))
        with Image.open(io.BytesIO(raster)) as image:
            page = Image.new("RGB", image.size, "white")
            page.paste(image, mask=image.getchannel("A"))
        if name == "angled-accidentals":
            page = page.rotate(1.4, Image.Resampling.BICUBIC, fillcolor="white")
        elif name == "faint-ledgers":
            page = ImageEnhance.Contrast(page.filter(ImageFilter.GaussianBlur(0.6))).enhance(0.55)
        page.save(HERE / f"{name}.png")


if __name__ == "__main__":
    main()
