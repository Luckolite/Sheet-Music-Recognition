# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Model inference and a file bridge to the platform-independent Java decoder."""
import gzip
import hashlib
import io
import json
import os
import struct
import subprocess
import tempfile
import zlib
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from contextlib import nullcontext
from functools import lru_cache
from pathlib import Path
from typing import NamedTuple

import numpy as np
from PIL import ExifTags, Image, ImageOps
from .runtime import inference_interpreter, java_executable
from .whitening import PaperBackgroundWhitening, WhiteningResult, MODEL_SHA256 as PAPER_FIELD_SHA256, RASTER_POLICY

MODEL_SHA256 = "92ab7c34c27cb704d95f0cde247b3611d1563fdf1c114b9f9817abb18a7b6a93"
MODEL_NAME = "music_sheets_v4_float16.tflite"
_OCR_NUMBER = struct.Struct(">i5f")
_OCR_METER = struct.Struct(">iii")
_OCR_WORD_BOUNDS = struct.Struct(">4f")


def tile_starts(size):
    """Tail tiles retain the app's exact 320/192 ownership order."""
    if size < 1:
        raise ValueError("Image dimensions must be positive")
    last = max(0, size - 320)
    return [*range(0, last, 192), last]


@lru_cache(maxsize=1)
def _tile_edges():
    axis = np.arange(320, dtype=np.int16)
    distance = np.minimum(axis, 319 - axis) + 1
    edges = np.minimum.outer(distance, distance).astype(np.uint8)
    edges.setflags(write=False)
    return edges


def _axis_confidence(size, starts, axis):
    length = min(size, len(axis))
    best = np.zeros(size, dtype=np.uint8)
    for start in starts:
        span = best[start:start + length]
        np.maximum(span, axis[:length], out=span)
    return best


def _tile_windows(height, width, weights, rows, columns):
    ch, cw = min(320, height), min(320, width)
    edge = weights[:ch, :cw]
    if weights is _tile_edges():
        if len(rows) == len(columns) == 1:
            yield (rows[0], columns[0]), True
            return
        # For a rectangular grid the best edge distance is the minimum
        # of the best horizontal and vertical distances at each pixel.
        axis = weights.max(axis=0)
        vertical = _axis_confidence(height, (row.start for row in rows), axis)
        horizontal = _axis_confidence(width, (col.start for col in columns), axis)
        previous = None
        for row in rows:
            row_weights = vertical[row]
            row_key = row_weights.tobytes()
            if row_key != previous:
                masks = {}
                previous = row_key
            for column in columns:
                column_weights = horizontal[column]
                key = column_weights.tobytes()
                mask = masks.get(key)
                if mask is None:
                    mask = edge >= np.minimum.outer(row_weights, column_weights)
                    masks[key] = mask
                # All tied winners write in the original traversal order.
                yield (row, column), mask
        return

    confidence = np.zeros((height, width), dtype=np.uint8)
    wins = np.empty((ch, cw), dtype=bool)
    for row in rows:
        for column in columns:
            local = confidence[row, column]
            np.greater_equal(edge, local, out=wins)
            np.copyto(local, edge, where=wins, casting="unsafe")
            yield (row, column), wins


def _repeated_tile_plan(gray, rows, columns):
    # Identical tiles must match on the sample grid. Check every pixel of candidate pairs
    # before reuse, including thin staff rules and marks between sample points.
    samples = [hash(gray[row, column][::16, ::16].tobytes())
               for row in rows for column in columns]
    frequency = Counter(samples)
    positions = ((row, column) for row in rows for column in columns)
    keys = [zlib.crc32(np.ascontiguousarray(gray[row, column])) if frequency[sample] > 1 else None
            for sample, (row, column) in zip(samples, positions)]
    return _tile_cache_plan(keys)


def _affected_interval(ink):
    occupied = np.flatnonzero(ink)
    if not occupied.size:
        return None
    # The reviewed model's three pooling levels align outputs in groups of eight.
    # For a group starting at x, the input range is [x - 78, x + 85], inclusive,
    # including the dilated convolutions at the deepest level.
    first = max(0, (int(occupied[0]) - 85 + 7) // 8)
    last = min(39, (int(occupied[-1]) + 78) // 8)
    return slice(first * 8, (last + 1) * 8)


def _sparse_pairs(gray, regions, decisions):
    """Pair single-use tiles whose nonwhite pixels affect disjoint output rectangles."""
    frequencies = Counter(key for key, _ in decisions if key is not None)
    pairs = {}
    white = set()
    # A group of 32 leaves at most 16 later predictions (1.56 MiB) in flight.
    for start in range(0, len(regions), 32):
        available = []
        for index in range(start, min(start + 32, len(regions))):
            key, _ = decisions[index]
            tile = gray[regions[index]]
            if key is not None and frequencies[key] > 1:
                if tile[0, 0] == 255 and tile.min() == 255:
                    white.add(index)
                continue
            ink = tile != 255
            rows = _affected_interval(ink.any(axis=1))
            if rows is None:
                white.add(index)
                continue
            columns = _affected_interval(ink.any(axis=0))
            if rows == columns == slice(0, 320):
                continue
            bounds = rows, columns
            for slot, (earlier, other) in enumerate(available):
                y, x = other
                if (y.stop <= rows.start or rows.stop <= y.start or
                        x.stop <= columns.start or columns.stop <= x.start):
                    pairs[earlier] = index, other, bounds
                    available.pop(slot)
                    break
            else:
                available.append((index, bounds))
    # A white reference costs one invocation unless the page already requires it.
    if len(pairs) < (1 if white else 2):
        return {}, set()
    return pairs, white


def _tile_cache_plan(keys, capacity=64):
    """Retain as many successive exact-tile reuse opportunities as fit in memory."""
    keep = bytearray(len(keys))
    # Find the next request for each key before choosing which predictions to hold.
    following = {}
    next_use = [None] * len(keys)
    for index in range(len(keys) - 1, -1, -1):
        key = keys[index]
        if key is not None:
            next_use[index] = following.get(key)
            following[key] = index
    active = {}
    for index, (key, end) in enumerate(zip(keys, next_use)):
        active.pop(key, None)
        if end is None:
            continue
        keep[index] = 1
        active[key] = (end, index)
        if len(active) > capacity:
            # Dropping the interval that ends last leaves room for the most later hits.
            victim = max(active, key=active.get)
            _, start = active.pop(victim)
            keep[start] = 0
    return zip(keys, keep)


def grayscale(image, width=2048):
    if not 64 <= width <= 4096:
        raise ValueError("Analysis width must be 64..4096")
    image.load()
    # Pillow copies even an unrotated image. The resize below already creates
    # a new image, so only make an orientation copy when the pixels need it.
    if image.getexif().get(ExifTags.Base.Orientation, 1) in (2, 3, 4, 5, 6, 7, 8):
        image = ImageOps.exif_transpose(image)
    height = max(1, round(image.height * width / image.width))
    if width * height > 20_000_000:
        raise ValueError("Analysis image exceeds 20 million pixels")
    if image.mode in ("L", "RGB") and "transparency" not in image.info:
        gray = image if image.mode == "L" else image.convert("L")
    else:
        white = Image.new("RGBA", image.size, "white")
        white.alpha_composite(image if image.mode == "RGBA" else image.convert("RGBA"))
        gray = white.convert("L")
    if gray.size != (width, height):
        gray = gray.resize((width, height), Image.Resampling.BILINEAR)
    return np.asarray(gray, dtype=np.uint8)


def write_page(path, labels, gray, annotations=None):
    height, width = gray.shape
    if labels.shape != gray.shape or labels.dtype != np.uint8 or gray.dtype != np.uint8:
        raise ValueError("Expected same-size uint8 mask and grayscale arrays")
    if annotations is not None and not isinstance(annotations, dict):
        raise ValueError("Each page's annotations must be an object")
    # The decoder reads this temporary file once. Favor fast compression.
    # Buffer the small annotation records so they reach gzip in batches.
    with gzip.open(path, "wb", compresslevel=3) as compressed, io.BufferedWriter(compressed) as stream:
        stream.write(struct.pack(">III", 0x52535031, width, height))
        stream.write(memoryview(np.ascontiguousarray(labels)))
        stream.write(memoryview(np.ascontiguousarray(gray)))
        if annotations is None:
            return
        stream.write(struct.pack(">I", 0x4F435231))
        for field in ("measureNumbers", "tempoNumbers", "restCounts"):
            rows = annotations.get(field, [])
            if len(rows) > 10000:
                raise ValueError("Too many OCR tokens")
            stream.write(struct.pack(">i", len(rows)))
            packed = bytearray(len(rows) * _OCR_NUMBER.size)
            for index, row in enumerate(rows):
                _OCR_NUMBER.pack_into(packed, index * _OCR_NUMBER.size, row["value"],
                                      row["left"], row["top"], row["right"], row["bottom"],
                                      row.get("annotationLeft", row["left"]))
            if packed:
                stream.write(packed)
        def write_words(words):
            if len(words) > 10000:
                raise ValueError("Too many OCR words")
            stream.write(struct.pack(">i", len(words)))
            for word in words:
                text = word["text"].encode("utf-8")
                if len(text) > 10000:
                    raise ValueError("OCR word too long")
                stream.write(struct.pack(">i", len(text)) + text)
                stream.write(_OCR_WORD_BOUNDS.pack(word["left"], word["top"], word["right"], word["bottom"]))
        write_words(annotations.get("words", []))
        meters = annotations.get("meters", [])
        if len(meters) > 10000:
            raise ValueError("Too many meter changes")
        stream.write(struct.pack(">i", len(meters)))
        packed = bytearray(len(meters) * _OCR_METER.size)
        for index, meter in enumerate(meters):
            _OCR_METER.pack_into(packed, index * _OCR_METER.size, meter["measureIndex"],
                                 meter["numerator"], meter["denominator"])
        if packed:
            stream.write(packed)
        write_words(annotations.get("tabWords", []))


class _PreparedPage(NamedTuple):
    gray: np.ndarray
    labels: np.ndarray
    annotations: dict
    corrected: WhiteningResult


class Interpreter:
    """One reusable CPU interpreter. Use one instance per worker; calls are not thread-safe."""
    def __init__(self, model_path=None, threads=2):
        if not 1 <= threads <= 32:
            raise ValueError("threads must be 1..32")
        self.jar = Path(__file__).with_name("interpreter.jar")
        if not self.jar.is_file():
            raise RuntimeError("Core JAR missing: run python scripts/build_java.py before installing from source")
        path = Path(model_path) if model_path else Path(__file__).parent / "data" / MODEL_NAME
        if not path.is_file():
            raise FileNotFoundError("Model missing: run python scripts/build_java.py or use the release wheel")
        digest = hashlib.sha256()
        with path.open("rb") as model:
            while chunk := model.read(256 * 1024):
                digest.update(chunk)
        self.model_sha256 = digest.hexdigest()
        if model_path is None and self.model_sha256 != MODEL_SHA256:
            raise ValueError("Bundled model checksum mismatch")
        interpreter = inference_interpreter(bundled=model_path is None)
        self.runtime = interpreter(model_path=str(path), num_threads=threads)
        self.runtime.allocate_tensors()
        self.input = self.runtime.get_input_details()[0]
        self.output = self.runtime.get_output_details()[0]
        if self.input["shape"].tolist() != [1, 3, 320, 320] or self.input["dtype"] != np.float32:
            raise ValueError("Expected float32 input [1,3,320,320]")
        if self.output["shape"].tolist() != [1, 320, 320] or self.output["dtype"] != np.int64:
            raise ValueError("Expected int64 class IDs [1,320,320]")
        self.edge = _tile_edges()
        # Pairing relies on the spatial support of this exact graph.
        self._sparse_pairs_enabled = model_path is None and self.model_sha256 == (
            "92ab7c34c27cb704d95f0cde247b3611d1563fdf1c114b9f9817abb18a7b6a93")
        self._threads = threads
        self.ocr = None
        self.paper_whitening = PaperBackgroundWhitening(threads=threads)

    def interpret_document(self, source, *, pages=None, meter=(4, 4), bpm=120,
                           key_fifths=0, width=2048, annotations=None, progress=None):
        """Recognize an image or PDF path and return the CLI's score document.

        PDF page numbers are one-based and strictly increasing. By default,
        all pages are read. If supplied, progress(completed, total, source_page)
        runs synchronously after each selected page is recognized.
        """
        from .document import interpret_document

        return interpret_document(self, source, pages=pages, meter=meter, bpm=bpm,
                                  key_fifths=key_fifths, width=width,
                                  annotations=annotations, progress=progress)

    def predict(self, gray):
        if not isinstance(gray, np.ndarray) or gray.ndim != 2 or gray.dtype != np.uint8:
            raise ValueError("Expected a 2D uint8 grayscale array")
        height, width = gray.shape
        if not width or not height or gray.size > 20_000_000:
            raise ValueError("Invalid image dimensions")
        labels = np.zeros_like(gray)
        ch, cw = min(320, height), min(320, width)
        rows = [slice(top, top + ch) for top in tile_starts(height)]
        columns = [slice(left, left + cw) for left in tile_starts(width)]
        bundled = getattr(self, "model_sha256", None) == MODEL_SHA256
        tensor_view = getattr(self.runtime, "tensor", None) if bundled else None
        if tensor_view is None:
            staging = np.empty((1, 3, 320, 320), dtype=np.float32)
            if ch < 320 or cw < 320:
                staging.fill(255)
        else:
            input_buffer = tensor_view(self.input["index"])
            output_buffer = tensor_view(self.output["index"])
        # Only the reviewed bundled graph has the deterministic-output contract.
        # Custom models keep one inference call per tile, including stateful models.
        reuse_predictions = (height > ch or width > cw) and bundled
        plan = _repeated_tile_plan(gray, rows, columns) if reuse_predictions else None
        repeated = {}
        pairs, blank_tiles = {}, set()
        later = {}
        paper = None
        if (plan is not None and ch == cw == 320 and len(rows) * len(columns) >= 3
                and getattr(self, "_sparse_pairs_enabled", False)):
            regions = [(r, c) for r in rows for c in columns]
            decisions = list(plan)
            pairs, blank_tiles = _sparse_pairs(gray, regions, decisions)
            plan = iter(decisions)
            if pairs:
                paper = getattr(self, "_white_prediction", None)
                if paper is None:
                    if bundled and type(self.runtime).__module__ == "ai_edge_litert.interpreter":
                        # The pinned graph and runtime classify every pixel of a white tile as background.
                        paper = np.broadcast_to(np.uint8(0), (320, 320))
                    else:
                        paper = Interpreter.predict(self, np.full((320, 320), 255, np.uint8))
                        paper.setflags(write=False)
                    self._white_prediction = paper
        for index, (region, wins) in enumerate(_tile_windows(height, width, self.edge, rows, columns)):
            tile = gray[region]
            cached = pixels = None
            if plan is not None:
                key, keep = next(plan)
                cached = later.pop(index, None)
                if cached is None and index in blank_tiles:
                    cached = paper
                if cached is not None:
                    np.copyto(labels[region], cached, where=wins)
                    continue
                entry = repeated.pop(key, None)
                if entry is not None and np.array_equal(tile, entry[0]):
                    cached = entry[1]
                    if keep:
                        repeated[key] = entry
                elif keep:
                    pixels = tile.copy(order="C")
            if cached is not None:
                prediction = cached
            else:
                if pixels is not None:
                    tile = pixels
                pair = pairs.get(index)
                if pair is not None:
                    partner, first_box, second_box = pair
                    tile = np.minimum(tile, gray[regions[partner]])
                if tensor_view is None:
                    tensor = staging
                    tensor[0, :, :ch, :cw] = tile
                    self.runtime.set_tensor(self.input["index"], tensor)
                else:
                    tensor = input_buffer()
                    # The pinned bundled graph slices the first channel before normalization.
                    if ch < 320 or cw < 320:
                        tensor[0, 0].fill(255)
                    tensor[0, 0, :ch, :cw] = tile
                # TFLite may move its arena during invoke. Hold only the getter across calls.
                del tensor
                self.runtime.invoke()
                prediction = (self.runtime.get_tensor(self.output["index"])
                              if tensor_view is None else output_buffer())[0]
                if prediction.min() < 0 or prediction.max() > 5:
                    del prediction
                    raise ValueError("Model returned classes outside 0..5")
                if pair is not None:
                    next_tile = paper.copy()
                    next_tile[second_box] = prediction[second_box]
                    later[partner] = next_tile
                    first_tile = paper.copy()
                    first_tile[first_box] = prediction[first_box]
                    prediction = first_tile
                if pixels is not None:
                    # Only complete input equality authorizes prediction reuse.
                    prediction = prediction.astype(np.uint8)
                    repeated[key] = pixels, prediction
            np.copyto(labels[region], prediction[:ch, :cw], where=wins, casting="unsafe")
            del prediction
        return labels

    def interpret(self, image, *, meter=(4, 4), key_fifths=0, width=2048, annotations=None,
                  page_index=None, instrument=None):
        """Recognize an image; optional page_index is its zero-based physical source page."""
        self._validate_decode_context(meter, key_fifths, page_index, instrument)
        prepared = self._prepare_page(image, width, annotations)
        return self._decode_page(prepared, meter, key_fifths, page_index, instrument)

    @staticmethod
    def _validate_decode_context(meter, key_fifths, page_index, instrument):
        if instrument not in (None, "violin"):
            raise ValueError("instrument must be None or 'violin' (standard tuning)")
        if page_index is not None and (type(page_index) is not int or not 0 <= page_index <= 100000):
            raise ValueError("page_index must be a zero-based physical page in 0..100000")
        if len(meter) != 2 or not all(isinstance(n, int) for n in meter):
            raise ValueError("meter must be a numerator/denominator integer pair")
        numerator, denominator = meter
        if not 1 <= numerator <= 32 or denominator not in (1, 2, 4, 8, 16, 32):
            raise ValueError("Invalid meter")
        if not isinstance(key_fifths, int) or not -7 <= key_fifths <= 7:
            raise ValueError("key_fifths must be -7..7")

    def _read_annotations(self, gray, annotations):
        from .ocr import LocalOcr, page_annotations

        if gray[0, 0] == 255 and gray.min() == 255:
            words = []
        else:
            if self.ocr is None:
                self.ocr = LocalOcr(threads=self._threads)
            words = self.ocr.words(gray)
        if annotations is None:
            return page_annotations(words, [])
        return dict(page_annotations(words, annotations["tabWords"]), **annotations)

    def _prepare_page(self, image, width, annotations):
        gray = grayscale(image, width)
        needs_ocr = annotations is None or ("tabWords" in annotations and "words" not in annotations)
        executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="score-ocr") if needs_ocr else nullcontext()
        with executor as worker:
            ocr = worker.submit(self._read_annotations, gray, annotations) if worker is not None else None
            labels = self.predict(gray)
            corrected = self.paper_whitening.correct(gray, labels)
            if ocr is not None:
                annotations = ocr.result()
        return _PreparedPage(gray, labels, annotations, corrected)

    def _decode_page(self, prepared, meter, key_fifths, page_index, instrument, *, run_decoder=None):
        gray, labels, annotations, corrected = prepared
        numerator, denominator = meter
        with tempfile.TemporaryDirectory(prefix="sheet-music-recognition-") as folder:
            page = Path(folder) / "page.page.gz"
            output = Path(folder) / "score.json"
            write_page(page, labels, gray, annotations)
            arguments = [str(page), str(output), str(numerator), str(denominator), str(key_fifths)]
            if corrected.changed:
                decoder_raster = Path(folder) / "decoder.gray"
                decoder_raster.write_bytes(memoryview(np.ascontiguousarray(corrected.decoder_gray)))
                arguments.extend(["--decoder-gray", str(decoder_raster)])
            if page_index is not None:
                arguments.extend(["--page-index", str(page_index)])
            if instrument is not None:
                arguments.extend(["--instrument", instrument])
            if run_decoder is None:
                command = [java_executable(), "-Xmx1g", "-jar", str(self.jar), *arguments]
                run = subprocess.run(command, capture_output=True, text=True)
                if run.returncode:
                    raise RuntimeError("Decoder failed: " + run.stderr.strip())
            else:
                run_decoder(arguments)
            result = json.loads(output.read_text(encoding="utf-8"))
        result["paperWhitening"] = dict(policy=RASTER_POLICY, modelSha256=PAPER_FIELD_SHA256,
                                       segmentationRasterSha256=corrected.raw_sha256,
                                       annotationRasterSha256=corrected.raw_sha256,
                                       decoderRasterSha256=corrected.decoder_sha256,
                                       changed=corrected.changed)
        result["modelSha256"] = self.model_sha256
        result["initialMeter"] = list(meter)
        result["initialKeyFifths"] = key_fifths
        result["warnings"] = ["Experimental recognition: review pitches, accidentals, ties and timing.",
                              "OCR can miss small or faint text; the initial meter is an argument, not an automatic reading."]
        result["warnings"].extend(result.get("tablatureWarnings", []))
        if not result["events"]:
            result["warnings"].append("No playable notes detected on this page.")
        return result
