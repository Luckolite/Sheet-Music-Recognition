# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Shared image and PDF document recognition for the Python API and CLI."""
import hashlib
import io
from pathlib import Path

from PIL import Image

from .boundary_ties import resolve_boundary_ties
from .clock_context import bind_document


def interpret_document(engine, source, *, pages=None, meter=(4, 4), bpm=120,
                       key_fifths=0, width=2048, annotations=None, progress=None):
    """Recognize a file. Call progress(completed, total, source_page) after each page."""
    source = Path(source)
    if not 15 <= bpm <= 400:
        raise ValueError("BPM must be 15..400")
    meter = tuple(meter)
    if len(meter) != 2 or any(type(value) is not int for value in meter):
        raise ValueError("meter must be a numerator/denominator integer pair")
    if type(key_fifths) is not int:
        raise ValueError("key_fifths must be -7..7")
    source_bytes = source.read_bytes()
    source_sha = hashlib.sha256(source_bytes).hexdigest()
    initial_meter = list(meter)
    key = key_fifths
    results = []

    def check_annotations(count):
        if annotations is not None and len(annotations) != count:
            raise ValueError("Annotation count must match the number of selected pages")

    def process(image, page_number, source_count, selected_count, native_words=None):
        nonlocal key, meter
        page_annotations = annotations[len(results)] if annotations is not None else (
            {"tabWords": native_words} if native_words else None
        )
        page = engine.interpret(image, meter=meter, key_fifths=key, width=width,
                                page_index=page_number - 1, annotations=page_annotations)
        score = page["score"]
        score["clockContextSource"] = bind_document(
            score.get("clockContextSource"), len(score["notes"]), len(score["measures"]),
            len(score.get("rests", [])), source_sha, page_number - 1, source_count,
        )
        page["sourcePage"] = page_number
        results.append(page)
        if score["keyChanges"]:
            key = score["keyChanges"][-1]["fifths"]
        if score["meterChanges"]:
            last = max(score["meterChanges"], key=lambda change: change["measureIndex"])
            meter = (last["numerator"], last["denominator"])
        if progress is not None:
            progress(len(results), selected_count, page_number)

    if source.suffix.lower() == ".pdf":
        try:
            import pypdfium2 as pdfium
        except ImportError as error:
            raise ValueError("PDF input needs the pdf extra: pip install 'score-reader[pdf]'") from error
        from .pdf_annotations import tab_words, tuning_header

        pdf = pdfium.PdfDocument(source_bytes)
        try:
            source_count = len(pdf)
            selected = list(range(1, source_count + 1)) if pages is None else list(pages)
            if not selected or any(type(n) is not int or not 1 <= n <= source_count for n in selected):
                raise ValueError("PDF page number out of range")
            if selected != sorted(set(selected)):
                raise ValueError("Choose unique PDF pages in increasing reading order")
            check_annotations(len(selected))
            first = pdf[0]
            try:
                header = tuning_header(first)
            finally:
                first.close()
            for number in selected:
                page = pdf[number - 1]
                try:
                    bitmap = page.render(scale=2400 / page.get_width())
                    try:
                        with bitmap.to_pil() as image:
                            process(image, number, source_count, len(selected), tab_words(page, header))
                    finally:
                        bitmap.close()
                finally:
                    page.close()
        finally:
            pdf.close()
    else:
        if pages is not None:
            raise ValueError("--pages is for PDF input")
        check_annotations(1)
        with Image.open(io.BytesIO(source_bytes)) as image:
            process(image, 1, 1, 1)

    return resolve_boundary_ties({
        "schemaVersion": 1,
        "inputName": source.name,
        "initialBpm": bpm,
        "initialMeter": initial_meter,
        "initialKeyFifths": key_fifths,
        "pages": results,
    })
