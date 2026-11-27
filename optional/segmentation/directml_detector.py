# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Serve the optional DirectML OCR detector over a caller-owned scratch mapping."""
import argparse
import hashlib
import mmap
from pathlib import Path
import struct
import sys

import numpy as np


MODEL_SHA256 = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
SUPPORTED_SHAPES = {(480, 128), (2048, 64), (2048, 96), (2048, 160)}
_MAX_PIXELS = 2048 * 160
_OUTPUT_OFFSET = _MAX_PIXELS * 3 * 4
_MAPPING_BYTES = _MAX_PIXELS * 4 * 4
_HEADER = struct.Struct(">II")


def _open_detector(model, adapter, width, height):
    import onnxruntime as ort

    provider = "DmlExecutionProvider"
    if ort.__version__ != "1.23.0" or provider not in ort.get_available_providers():
        raise RuntimeError("DirectML detection requires the isolated ONNX Runtime 1.23.0 provider")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.enable_mem_pattern = False
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.log_severity_level = 3
    for index, dimension in enumerate((1, height, width)):
        options.add_free_dimension_override_by_name(f"DynamicDimension.{index}", dimension)
    instance = ort.InferenceSession(
        model, options, providers=[(provider, {"device_id": str(adapter)})]
    )
    instance.disable_fallback()
    if instance.get_providers()[0] != provider:
        raise RuntimeError("Requested DirectML detector adapter is unavailable")
    return instance


def _serve(model, shared, adapter, source, destination):
    sessions = {}
    while header := source.read(_HEADER.size):
        if len(header) != _HEADER.size:
            raise EOFError("Incomplete detector request")
        width, height = shape = _HEADER.unpack(header)
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
    args = parser.parse_args(argv)
    if args.adapter < 0:
        raise ValueError("DirectML adapter index must be nonnegative")
    model = args.model.read_bytes()
    if hashlib.sha256(model).hexdigest() != MODEL_SHA256:
        raise ValueError("Detector model checksum mismatch")
    with args.mapping.open("r+b") as file, mmap.mmap(file.fileno(), 0) as shared:
        if len(shared) != _MAPPING_BYTES:
            raise ValueError("Invalid detector mapping size")
        _serve(model, shared, args.adapter, sys.stdin.buffer, sys.stdout.buffer)
