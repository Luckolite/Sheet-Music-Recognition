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
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from pathlib import Path
from typing import NamedTuple

import numpy as np
from PIL import ExifTags, Image, ImageOps
from .runtime import java_executable
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
        white.alpha_composite(image.convert("RGBA"))
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
        self.model_sha256 = hashlib.sha256(path.read_bytes()).hexdigest()
        if model_path is None and self.model_sha256 != MODEL_SHA256:
            raise ValueError("Bundled model checksum mismatch")
        try:
            import tensorflow as tf
        except ImportError as error:
            raise RuntimeError("Recognition dependencies are missing. Run: pip install --upgrade score-reader") from error
        self.runtime = tf.lite.Interpreter(model_path=str(path), num_threads=threads)
        self.runtime.allocate_tensors()
        self.input = self.runtime.get_input_details()[0]
        self.output = self.runtime.get_output_details()[0]
        if self.input["shape"].tolist() != [1, 3, 320, 320] or self.input["dtype"] != np.float32:
            raise ValueError("Expected float32 input [1,3,320,320]")
        if self.output["shape"].tolist() != [1, 320, 320] or self.output["dtype"] != np.int64:
            raise ValueError("Expected int64 class IDs [1,320,320]")
        self.edge = _tile_edges()
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
        confidence = np.zeros_like(gray)
        white_prediction = None
        ch, cw = min(320, height), min(320, width)
        edge = self.edge[:ch, :cw]
        wins = np.empty((ch, cw), dtype=bool)
        columns = [slice(left, left + cw) for left in tile_starts(width)]
        tensor = np.empty((1, 3, 320, 320), dtype=np.float32)
        if height < 320 or width < 320:
            tensor.fill(255)
        tensor_pixels = tensor[0, :, :ch, :cw]
        # Only the reviewed bundled graph has the deterministic-output contract.
        # Custom models keep one inference call per tile, including stateful models.
        reuse_white = getattr(self, "model_sha256", None) == MODEL_SHA256
        for top in tile_starts(height):
            rows = slice(top, top + ch)
            for column in columns:
                region = rows, column
                tile = gray[region]
                # The fixed padding is white too, so only inspect source pixels.
                exact_white = reuse_white and tile.min() == 255
                if exact_white and white_prediction is not None:
                    prediction = white_prediction
                else:
                    tensor_pixels[...] = tile
                    self.runtime.set_tensor(self.input["index"], tensor)
                    self.runtime.invoke()
                    prediction = self.runtime.get_tensor(self.output["index"])[0]
                    if prediction.min() < 0 or prediction.max() > 5:
                        raise ValueError("Model returned classes outside 0..5")
                    if exact_white:
                        # Cache owned, validated labels in the destination dtype.
                        white_prediction = prediction.astype(np.uint8)
                        prediction = white_prediction
                tile_confidence = confidence[region]
                np.greater_equal(edge, tile_confidence, out=wins)
                # Validated class IDs and the tile's maximum edge weight (160) fit in uint8.
                np.copyto(labels[region], prediction[:ch, :cw], where=wins, casting="unsafe")
                np.copyto(tile_confidence, edge, where=wins, casting="unsafe")
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

        if self.ocr is None:
            self.ocr = LocalOcr()
        words = self.ocr.words(gray)
        if annotations is None:
            return page_annotations(words, [])
        return dict(page_annotations(words, annotations["tabWords"]), **annotations)

    def _prepare_page(self, image, width, annotations):
        gray = grayscale(image, width)
        if annotations is None or ("tabWords" in annotations and "words" not in annotations):
            with ThreadPoolExecutor(max_workers=1, thread_name_prefix="score-ocr") as worker:
                ocr = worker.submit(self._read_annotations, gray, annotations)
                labels = self.predict(gray)
                corrected = self.paper_whitening.correct(gray, labels)
                annotations = ocr.result()
        else:
            labels = self.predict(gray)
            corrected = self.paper_whitening.correct(gray, labels)
        return _PreparedPage(gray, labels, annotations, corrected)

    def _decode_page(self, prepared, meter, key_fifths, page_index, instrument):
        gray, labels, annotations, corrected = prepared
        numerator, denominator = meter
        with tempfile.TemporaryDirectory(prefix="sheet-music-recognition-") as folder:
            page = Path(folder) / "page.page.gz"
            output = Path(folder) / "score.json"
            write_page(page, labels, gray, annotations)
            command = [java_executable(), "-Xmx1g", "-jar", str(self.jar), str(page), str(output),
                       str(numerator), str(denominator), str(key_fifths)]
            if corrected.changed:
                decoder_raster = Path(folder) / "decoder.gray"
                decoder_raster.write_bytes(memoryview(np.ascontiguousarray(corrected.decoder_gray)))
                command.extend(["--decoder-gray", str(decoder_raster)])
            if page_index is not None:
                command.extend(["--page-index", str(page_index)])
            if instrument is not None:
                command.extend(["--instrument", instrument])
            run = subprocess.run(command, capture_output=True, text=True)
            if run.returncode:
                raise RuntimeError("Decoder failed: " + run.stderr.strip())
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
