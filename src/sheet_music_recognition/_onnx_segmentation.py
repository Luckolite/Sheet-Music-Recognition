# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Optional ONNX segmentation for the supplied v3 model."""

import hashlib
from pathlib import Path
from threading import Lock

import numpy as np

from .reader import _tile_edges, _tile_windows, tile_starts


MODEL_SHA256 = "e436efe12ddc598add9540378d6772622a2ad9d7bdb1f9d4c0ab87e3144402a7"
GUARDED_MODEL_SHA256 = "fbd4c8a767538ff53eca8c10507d68ab81e60bd584a54cb2321f60101b3ba282"
WINDOW = 320
MAX_PAGE_PIXELS = 20_000_000
_CPU_PROVIDER = "CPUExecutionProvider"
_DML_PROVIDER = "DmlExecutionProvider"


def _read_model(path, digest):
    model = Path(path).read_bytes()
    if hashlib.sha256(model).hexdigest() != digest:
        raise ValueError("ONNX segmentation model checksum mismatch")
    return model


def _open_session(ort, model, threads, adapter=None, *, cpu_arena=True):
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.log_severity_level = 3
    if not cpu_arena:
        options.enable_cpu_mem_arena = False
    provider = _CPU_PROVIDER
    providers = [provider]
    if adapter is not None:
        provider = _DML_PROVIDER
        options.enable_mem_pattern = False
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        providers = [(provider, {"device_id": str(adapter)})]
    session = ort.InferenceSession(model, options, providers=providers)
    session.disable_fallback()
    if session.get_providers()[0] != provider:
        raise RuntimeError("Requested segmentation provider is unavailable")
    inputs = session.get_inputs()
    outputs = session.get_outputs()
    expected_types = ["tensor(int64)"]
    if adapter is not None:
        expected_types.append("tensor(bool)")
    if (
        len(inputs) != 1 or inputs[0].shape != [1, 3, WINDOW, WINDOW]
        or inputs[0].type != "tensor(float)" or [output.type for output in outputs] != expected_types
    ):
        raise ValueError("Unexpected ONNX segmentation tensor contract")
    return session, inputs[0].name


def _label_plane(prediction):
    if prediction.size != WINDOW * WINDOW or prediction.dtype != np.int64:
        raise ValueError("Unexpected ONNX segmentation output")
    if prediction.min() < 0 or prediction.max() > 5:
        raise ValueError("Invalid ONNX segmentation class")
    return prediction.reshape(WINDOW, WINDOW)


class OnnxTileSegmentation:
    """Keep the stock input, output and ownership rules for an optional ONNX runtime."""

    def __init__(self, model_path, threads=2, *, directml_adapter=None, guarded_model=None):
        import onnxruntime as ort

        if threads < 1:
            raise ValueError("ONNX segmentation requires a positive thread count")
        self._ort = ort
        self._stock_model = _read_model(model_path, MODEL_SHA256)
        self._threads = threads
        self._gpu = None
        self._cpu = None
        self._lock = Lock()
        if directml_adapter is not None:
            if directml_adapter < 0 or guarded_model is None:
                raise ValueError("DirectML requires an explicit adapter and the checked guard model")
            if ort.__version__ != "1.23.0" or _DML_PROVIDER not in ort.get_available_providers():
                raise RuntimeError("DirectML segmentation requires the isolated ONNX Runtime 1.23.0 provider")
            model = _read_model(guarded_model, GUARDED_MODEL_SHA256)
            self._gpu = _open_session(ort, model, threads, directml_adapter)
        else:
            self._load_cpu()

    def _load_cpu(self):
        if self._cpu is None:
            # Guarded GPU runs use CPU inference only for individual ambiguous tiles.
            # Release those work buffers before the rest of the page is decoded.
            self._cpu = _open_session(
                self._ort, self._stock_model, self._threads, cpu_arena=self._gpu is None
            )
            self._stock_model = None
        return self._cpu

    def _cpu_prediction(self, tensor):
        session, name = self._load_cpu()
        prediction, = session.run(None, {name: tensor})
        return _label_plane(prediction)

    def predict(self, gray):
        """Return one class per pixel of the supplied grayscale page."""
        if (not isinstance(gray, np.ndarray) or gray.dtype != np.uint8
                or gray.ndim != 2 or not gray.size or gray.size > MAX_PAGE_PIXELS):
            raise ValueError("Invalid grayscale page")
        with self._lock:
            return self._predict(gray)

    def _predict(self, gray):
        height, width = gray.shape
        rows = [slice(at, min(height, at + WINDOW)) for at in tile_starts(height)]
        columns = [slice(at, min(width, at + WINDOW)) for at in tile_starts(width)]
        labels = np.empty_like(gray)
        tensor = np.full((1, 3, WINDOW, WINDOW), 255, dtype=np.float32)
        white_cpu = None
        white_gpu = None
        for (row, column), owned in _tile_windows(height, width, _tile_edges(), rows, columns):
            plane = gray[row, column]
            h, w = plane.shape
            white = bool(np.all(plane == 255))
            tensor[0, :, :h, :w] = plane
            needs_cpu = self._gpu is None
            if self._gpu is not None:
                if white and white_gpu is not None:
                    prediction, flags = white_gpu
                else:
                    session, name = self._gpu
                    raw, flags = session.run(None, {name: tensor})
                    prediction = _label_plane(raw)
                    if flags.shape != (1, 1, WINDOW, WINDOW) or flags.dtype != np.bool_:
                        raise ValueError("Unexpected ONNX segmentation ambiguity flags")
                    if white:
                        white_gpu = prediction, flags
                needs_cpu = bool(np.any(flags[0, 0, :h, :w], where=owned))
            if needs_cpu:
                if not white or white_cpu is None:
                    prediction = self._cpu_prediction(tensor)
                    if white:
                        white_cpu = prediction
                else:
                    prediction = white_cpu
            np.copyto(labels[row, column], prediction[:h, :w], where=owned, casting="unsafe")
        return labels
