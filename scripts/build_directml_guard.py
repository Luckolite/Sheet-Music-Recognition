# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Add ambiguity flags to the checked desktop segmentation model."""
import argparse
import hashlib
from pathlib import Path


STOCK_SHA256 = "e436efe12ddc598add9540378d6772622a2ad9d7bdb1f9d4c0ab87e3144402a7"
GUARDED_SHA256 = "fbd4c8a767538ff53eca8c10507d68ab81e60bd584a54cb2321f60101b3ba282"


def build(stock):
    import onnx
    from onnx import TensorProto, helper

    if onnx.__version__ != "1.19.1":
        raise RuntimeError("Build the guard with onnx==1.19.1")
    if hashlib.sha256(stock).hexdigest() != STOCK_SHA256:
        raise ValueError("Unexpected desktop segmentation model")
    model = onnx.load_model_from_string(stock)
    logits = model.graph.node[-1].input[0]
    model.graph.initializer.extend([
        helper.make_tensor("guard.two", TensorProto.INT64, [1], [2]),
        helper.make_tensor("guard.first", TensorProto.INT64, [1], [0]),
        helper.make_tensor("guard.second", TensorProto.INT64, [1], [1]),
        helper.make_tensor("guard.threshold", TensorProto.FLOAT, [], [0.0001]),
    ])
    model.graph.node.extend([
        helper.make_node("TopK", [logits, "guard.two"], ["guard.scores", "guard.indices"], axis=1),
        helper.make_node("Gather", ["guard.scores", "guard.first"], ["guard.high"], axis=1),
        helper.make_node("Gather", ["guard.scores", "guard.second"], ["guard.low"], axis=1),
        helper.make_node("Sub", ["guard.high", "guard.low"], ["guard.margin"]),
        helper.make_node("LessOrEqual", ["guard.margin", "guard.threshold"], ["guard.retry"]),
    ])
    model.graph.output.append(
        helper.make_tensor_value_info("guard.retry", TensorProto.BOOL, [1, 1, 320, 320])
    )
    onnx.checker.check_model(model)
    guarded = model.SerializeToString()
    if hashlib.sha256(guarded).hexdigest() != GUARDED_SHA256:
        raise ValueError("Unexpected generated DirectML guard")
    return guarded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    guarded = build(args.stock.read_bytes())
    with args.output.open("xb") as target:
        target.write(guarded)


if __name__ == "__main__":
    main()
