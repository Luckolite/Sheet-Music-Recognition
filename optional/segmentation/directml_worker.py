# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Segment a grayscale NumPy raster using the optional Windows DirectML provider."""
import argparse
from pathlib import Path

import numpy as np

from sheet_music_recognition._onnx_segmentation import OnnxTileSegmentation


def main():
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
