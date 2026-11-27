# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Serve optional DirectML OCR models over a caller-owned scratch mapping."""
import argparse
import hashlib
import mmap
from pathlib import Path
import struct
import sys

import numpy as np


MODEL_SHA256 = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
RECOGNIZER_SHA256 = "b20bd37c168a570f583afbc8cd7925603890efbcdc000a59e22c269d160b5f5a"
SUPPORTED_SHAPES = {(480, 128), (2048, 64), (2048, 96), (2048, 160)}
_MAX_PIXELS = 2048 * 160
_OUTPUT_OFFSET = _MAX_PIXELS * 3 * 4
_MAPPING_BYTES = _MAX_PIXELS * 4 * 4
_HEADER = struct.Struct(">II")
_RECOGNIZE = (0, 48)
_RECOGNIZER_WIDTH = 320
_VOCABULARY = 504


def _open_model(model, adapter, dimensions, threads):
    import onnxruntime as ort

    provider = "DmlExecutionProvider"
    if ort.__version__ != "1.23.0" or provider not in ort.get_available_providers():
        raise RuntimeError("DirectML OCR requires the isolated ONNX Runtime 1.23.0 provider")
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.enable_mem_pattern = False
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.log_severity_level = 3
    for index, dimension in enumerate(dimensions):
        options.add_free_dimension_override_by_name(f"DynamicDimension.{index}", dimension)
    instance = ort.InferenceSession(
        model, options, providers=[(provider, {"device_id": str(adapter)})]
    )
    instance.disable_fallback()
    if instance.get_providers()[0] != provider:
        raise RuntimeError("Requested DirectML OCR adapter is unavailable")
    return instance


def _open_detector(model, adapter, width, height):
    return _open_model(model, adapter, (1, height, width), 2)


def _recognize(recognizer, shared):
    shape = (1, 3, _RECOGNIZE[1], _RECOGNIZER_WIDTH)
    tensor = np.ndarray(shape, dtype="<f4", buffer=shared)
    scores, = recognizer.run(None, {"x": tensor})
    if (scores.ndim != 3 or scores.shape[0] != 1 or scores.shape[2] != _VOCABULARY
            or scores.dtype != np.float32 or not 1 <= scores.shape[1] <= 256):
        raise ValueError("Unexpected recognizer output tensor")
    struct.pack_into("<I", shared, _OUTPUT_OFFSET, scores.shape[1])
    np.ndarray(scores.shape, dtype="<f4", buffer=shared, offset=_OUTPUT_OFFSET + 4)[:] = scores


def _serve(model, shared, adapter, source, destination, recognizer_path=None):
    sessions = {}
    recognizer = None
    while header := source.read(_HEADER.size):
        if len(header) != _HEADER.size:
            raise EOFError("Incomplete detector request")
        width, height = shape = _HEADER.unpack(header)
        if shape == _RECOGNIZE:
            response = b"R"
            if recognizer_path is not None:
                try:
                    if recognizer is None:
                        recognition_model = recognizer_path.read_bytes()
                        if hashlib.sha256(recognition_model).hexdigest() != RECOGNIZER_SHA256:
                            raise ValueError("Recognizer model checksum mismatch")
                        recognizer = _open_model(recognition_model, adapter, (1, _RECOGNIZER_WIDTH), 4)
                    _recognize(recognizer, shared)
                    response = b"K"
                except Exception as failure:
                    # A failed recognizer request leaves the existing detector sessions usable.
                    print(f"DirectML recognizer unavailable: {failure}", file=sys.stderr)
                    recognizer_path = recognizer = None
            destination.write(response)
            destination.flush()
            continue
        if shape not in SUPPORTED_SHAPES:
            raise ValueError("Unsupported detector shape")
        if shape not in sessions:
            sessions[shape] = _open_detector(model, adapter, width, height)
        tensor = np.frombuffer(shared, dtype="<f4", count=3 * width * height).reshape(
            1, 3, height, width
        )
        try:
            result, = sessions[shape].run(None, {"x": tensor})
        finally:
            del tensor
        if result.shape != (1, 1, height, width) or result.dtype != np.float32:
            raise ValueError("Unexpected detector output tensor")
        np.ndarray(result.shape, dtype="<f4", buffer=shared, offset=_OUTPUT_OFFSET)[:] = result
        destination.write(b"K")
        destination.flush()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("mapping", type=Path)
    parser.add_argument("--adapter", type=int, required=True)
    parser.add_argument("--recognizer-model", type=Path)
    args = parser.parse_args(argv)
    if args.adapter < 0:
        raise ValueError("DirectML adapter index must be nonnegative")
    model = args.model.read_bytes()
    if hashlib.sha256(model).hexdigest() != MODEL_SHA256:
        raise ValueError("Detector model checksum mismatch")
    with args.mapping.open("r+b") as file, mmap.mmap(file.fileno(), 0) as shared:
        if len(shared) != _MAPPING_BYTES:
            raise ValueError("Invalid detector mapping size")
        _serve(model, shared, args.adapter, sys.stdin.buffer, sys.stdout.buffer, args.recognizer_model)
