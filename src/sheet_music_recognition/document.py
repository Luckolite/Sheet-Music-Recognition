# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Shared image and PDF document recognition for the Python API and CLI."""
import hashlib
import io
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

from .boundary_ties import resolve_boundary_ties
from .clock_context import bind_document


def interpret_document(engine, source, *, pages=None, meter=(4, 4), bpm=120,
                       key_fifths=0, width=2048, annotations=None, progress=None, instrument=None):
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

    def accept(page, page_number, source_count, selected_count):
        nonlocal key, meter
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

    def recognize(image, number, page_annotations):
        context = {} if instrument is None else {"instrument": instrument}
        return engine.interpret(image, meter=meter, key_fifths=key, width=width,
                                page_index=number - 1, annotations=page_annotations, **context)

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
            header = ""
            if annotations is None:
                first = pdf[0]
                try:
                    header = tuning_header(first)
                finally:
                    first.close()

            def render(number, index, consume):
                page = pdf[number - 1]
                try:
                    bitmap = page.render(scale=2400 / page.get_width())
                    try:
                        with bitmap.to_pil() as image:
                            if annotations is None:
                                native_words = tab_words(page, header)
                                supplied = {"tabWords": native_words} if native_words else None
                            else:
                                supplied = annotations[index]
                            return consume(image, supplied)
                    finally:
                        bitmap.close()
                finally:
                    page.close()

            from .reader import Interpreter
            if len(selected) > 1 and getattr(engine.interpret, "__func__", None) is Interpreter.interpret:
                engine._validate_decode_context(meter, key, selected[0] - 1, instrument)

                def prepare(number, index):
                    return render(number, index, lambda image, supplied: engine._prepare_page(image, width, supplied))

                prepared = prepare(selected[0], 0)
                with ThreadPoolExecutor(max_workers=1, thread_name_prefix="score-decoder") as worker:
                    for index, number in enumerate(selected):
                        engine._validate_decode_context(meter, key, number - 1, instrument)
                        pending = worker.submit(
                            engine._decode_page, prepared, meter, key, number - 1, instrument)
                        try:
                            if index + 1 < len(selected):
                                prepared = prepare(selected[index + 1], index + 1)
                        except Exception:
                            # Surface the current page's outcome before the next page's failure.
                            accept(pending.result(), number, source_count, len(selected))
                            raise
                        accept(pending.result(), number, source_count, len(selected))
            else:
                for index, number in enumerate(selected):
                    page = render(number, index, lambda image, supplied: recognize(image, number, supplied))
                    accept(page, number, source_count, len(selected))
        finally:
            pdf.close()
    else:
        if pages is not None:
            raise ValueError("--pages is for PDF input")
        check_annotations(1)
        with Image.open(io.BytesIO(source_bytes)) as image:
            page = recognize(image, 1, annotations[0] if annotations is not None else None)
            accept(page, 1, 1, 1)

    return resolve_boundary_ties({
        "schemaVersion": 1,
        "inputName": source.name,
        "initialBpm": bpm,
        "initialMeter": initial_meter,
        "initialKeyFifths": key_fifths,
        "pages": results,
    })
