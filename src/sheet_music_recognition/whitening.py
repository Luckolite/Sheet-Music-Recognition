# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Paper and faded-ink correction for decoder grayscale."""
from dataclasses import dataclass
import hashlib
from pathlib import Path
import struct

import numpy as np

MODEL_NAME = "paper_field_v1.onnx"
MODEL_SHA256 = "dc27aec9f1d29cc423510277992523991fbbb568770a3acf73875107476ab460"
RASTER_POLICY = "original-segmentation-paper-field-decoder-v2"
MAX_PIXELS = 20_000_000
MIN_FADED_TONE = 96
MAX_FADED_TONE = 220
MIN_PAPER_CONTRAST = 32


@dataclass(frozen=True)
class WhiteningResult:
    decoder_gray: np.ndarray
    changed: bool
    raw_sha256: str
    decoder_sha256: str


def raster_sha256(gray):
    height, width = gray.shape
    digest = hashlib.sha256(struct.pack(">II", width, height))
    digest.update(memoryview(np.ascontiguousarray(gray)))
    return digest.hexdigest()


def _bright_paper(pixels):
    # The thresholds fall between byte values 239/240 and 249/250.
    # Count pixels on each side to avoid partitioning a float image unless
    # the two samples used for interpolation straddle a threshold.
    last = pixels.size - 1
    quantile_rank = last * .1
    median_rank = last * .5
    below_quantile = np.count_nonzero(pixels < 240)
    below_median = np.count_nonzero(pixels < 250)
    if below_quantile > np.ceil(quantile_rank) or below_median > np.ceil(median_rank):
        return False
    if below_quantile <= int(quantile_rank) and below_median <= int(median_rank):
        return True
    normalized = pixels.astype(np.float32) / 255
    return bool(np.quantile(normalized, .1) > .94 and np.median(normalized) > .98)


class PaperBackgroundWhitening:
    def __init__(self, model_path=None, threads=2):
        if type(threads) is not int or not 1 <= threads <= 32:
            raise ValueError("threads must be 1..32")
        self.model_path = Path(model_path) if model_path else Path(__file__).parent / "data" / MODEL_NAME
        self.threads = threads
        self._session = None

    def verify_model(self):
        if hashlib.sha256(self.model_path.read_bytes()).hexdigest() != MODEL_SHA256:
            raise ValueError("Unrecognized paper-field model checksum")

    def _inference(self, features):
        if self._session is None:
            import onnxruntime as ort
            self.verify_model()
            options = ort.SessionOptions()
            options.intra_op_num_threads = self.threads
            options.inter_op_num_threads = 1
            options.add_session_config_entry("session.force_spinning_stop", "1")
            self._session = ort.InferenceSession(str(self.model_path), options, providers=["CPUExecutionProvider"])
        paper, contrast = self._session.run(["paper", "contrast"], {"features": features[None]})
        expected = (1, 1, features.shape[1], features.shape[2])
        for field, minimum in ((paper, .23), (contrast, .35)):
            if (field.shape != expected or not np.isfinite(field).all()
                    or float(field.min()) < minimum - .00001 or float(field.max()) > 1.00001):
                raise ValueError("Invalid paper-field output")
        return paper[0, 0], contrast[0, 0]

    def correct(self, gray, labels=None):
        if not isinstance(gray, np.ndarray) or gray.dtype != np.uint8 or gray.ndim != 2:
            raise ValueError("Expected uint8 grayscale image")
        height, width = gray.shape
        if not width or not height or gray.size > MAX_PIXELS:
            raise ValueError("Invalid grayscale dimensions")
        if labels is not None and (not isinstance(labels, np.ndarray)
                                   or labels.dtype != np.uint8 or labels.shape != gray.shape):
            raise ValueError("Expected uint8 notation labels matching grayscale dimensions")
        import cv2
        raw_hash = raster_sha256(gray)

        kernel = np.ones((25, 25), np.uint8)
        paper_estimate = None
        if not _bright_paper(gray):
            paper_estimate = cv2.morphologyEx(gray, cv2.MORPH_CLOSE, kernel)
        if paper_estimate is None or _bright_paper(paper_estimate):
            output = self._darken_faded_ink(gray, labels, kernel, paper_estimate)
            return self._result(gray, output, raw_hash)
        scale = min(1., 960 / max(height, width))
        shape = (max(8, round(width * scale)), max(8, round(height * scale)))
        small = cv2.resize(gray.astype(np.float32), shape, interpolation=cv2.INTER_AREA) / 255
        estimate = cv2.morphologyEx(small, cv2.MORPH_CLOSE, kernel)
        features = np.stack((small, estimate)).astype(np.float32, copy=False)
        paper, contrast = self._inference(features)
        paper = cv2.resize(paper, (width, height), interpolation=cv2.INTER_LINEAR)
        contrast = cv2.resize(contrast, (width, height), interpolation=cv2.INTER_LINEAR)
        ink = np.maximum(0, 1 - gray / (255 * paper))
        output = np.rint(255 * (1 - ink / contrast)).clip(0, 255).astype(np.uint8)
        output = self._darken_faded_ink(output, labels, kernel)
        return self._result(gray, output, raw_hash)

    @staticmethod
    def _result(gray, output, raw_hash):
        if output is gray or np.array_equal(output, gray):
            return WhiteningResult(gray, False, raw_hash, raw_hash)
        return WhiteningResult(output, True, raw_hash, raster_sha256(output))

    @staticmethod
    def _darken_faded_ink(gray, labels, kernel, paper=None):
        if labels is None:
            return gray
        import cv2

        # Classes 1 through 5 are the model's notation classes. Require existing ink
        # and protect the antialiased edges around already dark strokes.
        candidates = ((labels >= 1) & (labels <= 5)
                      & (gray >= MIN_FADED_TONE) & (gray < MAX_FADED_TONE))
        if not candidates.any():
            return gray
        core = cv2.erode(gray, np.ones((3, 3), np.uint8))
        candidates &= core >= MIN_FADED_TONE
        if not candidates.any():
            return gray
        if paper is None:
            paper = cv2.morphologyEx(gray, cv2.MORPH_CLOSE, kernel)
        tones = gray[candidates].astype(np.int16)
        gain = np.minimum(core[candidates].astype(np.int16) - MIN_FADED_TONE + 1,
                          np.minimum(MAX_FADED_TONE - tones, (255 - tones) // 2))
        gain[paper[candidates].astype(np.int16) - tones < MIN_PAPER_CONTRAST] = 0
        if not gain.any():
            return gray
        output = gray.copy()
        output[candidates] = tones - gain
        return output
