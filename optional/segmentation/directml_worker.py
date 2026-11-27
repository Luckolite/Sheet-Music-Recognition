# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Segment a grayscale NumPy raster using the optional Windows DirectML provider."""
import argparse
import os
from pathlib import Path
import re
import sys

import numpy as np

from sheet_music_recognition._onnx_segmentation import OnnxTileSegmentation


def segment_service(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock_model", type=Path)
    parser.add_argument("requests", type=Path)
    parser.add_argument("--adapter", type=int, required=True)
    args = parser.parse_args(argv)
    root = args.requests.resolve(strict=True)
    segmenter = None
    for _ in range(2):
        command = sys.stdin.buffer.readline(34)
        if not command:
            return
        if not re.fullmatch(rb"[a-f0-9]{32}\n", command):
            raise ValueError("Invalid page request")
        request = root / command[:-1].decode("ascii")
        if request.resolve(strict=True).parent != root:
            raise ValueError("Page request escapes the session directory")
        source = request / "page.npy"
        staging = request / "labels.staging"
        destination = request / "labels.npy"
        if any(path.is_symlink() for path in (source, staging, destination)) or destination.exists():
            raise ValueError("Invalid page files")
        if segmenter is None:
            segmenter = OnnxTileSegmentation(
                args.stock_model, directml_adapter=args.adapter,
                guarded_model=args.stock_model.with_name("guarded-margin.onnx"),
            )
        labels = segmenter.predict(np.load(source, mmap_mode="r", allow_pickle=False))
        with staging.open("xb") as output:
            np.save(output, labels, allow_pickle=False)
        os.replace(staging, destination)


def main():
    if sys.argv[1:2] == ["--detector-service"]:
        from directml_detector import main as detect

        detect(sys.argv[2:])
        return
    if sys.argv[1:2] == ["--segmentation-service"]:
        segment_service(sys.argv[2:])
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock_model", type=Path)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--adapter", type=int, required=True)
    args = parser.parse_args()

    segmenter = OnnxTileSegmentation(
        args.stock_model,
        directml_adapter=args.adapter,
        guarded_model=args.stock_model.with_name("guarded-margin.onnx"),
    )
    labels = segmenter.predict(np.load(args.source, mmap_mode="r", allow_pickle=False))
    with args.destination.open("xb") as target:
        np.save(target, labels, allow_pickle=False)


if __name__ == "__main__":
    main()
