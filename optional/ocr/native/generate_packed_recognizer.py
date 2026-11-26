# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Generate the evaluated ARM64 graph without modifying recognizer weights."""

import argparse
from copy import deepcopy
from hashlib import sha256
from pathlib import Path

import numpy as np
import onnx
from onnx import helper as h, TensorProto as T, numpy_helper as nh

parser = argparse.ArgumentParser(
    description="Build the ARM64 packed OCR graph from the pinned model."
)
parser.add_argument("original_model", type=Path)
parser.add_argument("output_model", type=Path)
args = parser.parse_args()
if args.original_model.resolve() == args.output_model.resolve():
    parser.error("The output path must differ from the original model.")
original_bytes = args.original_model.read_bytes()
if (
    sha256(original_bytes).hexdigest()
    != "b20bd37c168a570f583afbc8cd7925603890efbcdc000a59e22c269d160b5f5a"
):
    parser.error("Expected the pinned Latin PP-OCRv5 recognizer.")
stock = onnx.load_model_from_string(original_bytes)
g = stock.graph
domain = "com.luckolite.spatialprobe"
stock.opset_import.add(domain=domain, version=1)
start = next(i for i, n in enumerate(g.node) if n.name == "Conv.13")
stop = next(i for i, n in enumerate(g.node) if n.name == "GlobalAveragePool.0")
original = deepcopy(g.node[start:stop])
prefix = "restored_stock_"
first = original[0].input[0]
last = original[-1].output[0]
external = {inp for n in g.node[stop:] for inp in n.input}
assert [out for n in original for out in n.output if out in external] == [last]
weights = {weight.name: weight for weight in g.initializer}
radius = 0
for node in original:
    if node.op_type == "Conv":
        attrs = {attr.name: h.get_attribute_value(attr) for attr in node.attribute}
        width = weights[node.input[1]].dims[3]
        assert attrs["strides"][1] == 1 and attrs["dilations"][1] == 1
        assert width == 1 or (width == 5 and attrs["pads"][1::2] == [2, 2])
        radius += (width - 1) // 2
    else:
        assert node.op_type in {"Identity", "Add", "Mul", "HardSwish"}
        if node.op_type in {"Add", "Mul"}:
            side = [weights[arg] for arg in node.input if arg in weights]
            assert len(side) == 1
            dims = list(side[0].dims)
            assert dims == [1] or dims[-2:] == [1, 1]
assert radius == 10
values = {v.name: v for v in g.value_info}
packed = deepcopy(original)
packed[0].input[0] = prefix + "packed"
packed[-1].output[0] = prefix + "packed_result"
original[-1].output[0] = prefix + "original"


def named(name):
    result = deepcopy(values[last])
    result.name = prefix + name
    return result


fast = h.make_graph(
    [
        h.make_node(
            "PackDepthwiseChain",
            [first],
            [prefix + "packed", prefix + "map"],
            name=prefix + "pack",
            domain=domain,
        ),
        *packed,
        h.make_node(
            "ExpandDepthwiseColumns",
            [prefix + "packed_result", prefix + "map"],
            [prefix + "expanded"],
            name=prefix + "expand",
            domain=domain,
        ),
    ],
    prefix + "fast",
    [],
    [named("expanded")],
    value_info=[
        h.make_tensor_value_info(prefix + "packed", T.FLOAT, [1, 240, None, None]),
        h.make_tensor_value_info(
            prefix + "packed_result", T.FLOAT, [1, 240, None, None]
        ),
        h.make_tensor_value_info(prefix + "map", T.INT32, [None]),
    ],
)
slow = h.make_graph(original, prefix + "slow", [], [named("original")])
width_axis = h.make_tensor(prefix + "width_axis", T.INT64, [], [3])
normal_width = h.make_tensor(prefix + "normal_width", T.INT64, [], [320])
g.initializer.extend([width_axis, normal_width])
del g.node[start:stop]
for index, n in enumerate(
    [
        h.make_node("Shape", ["x"], [prefix + "shape"], name=prefix + "shape"),
        h.make_node(
            "Gather",
            [prefix + "shape", width_axis.name],
            [prefix + "width"],
            name=prefix + "width",
        ),
        h.make_node(
            "Equal",
            [prefix + "width", normal_width.name],
            [prefix + "use_packed"],
            name=prefix + "gate",
        ),
        h.make_node(
            "If",
            [prefix + "use_packed"],
            [last],
            name=prefix + "dispatch",
            then_branch=fast,
            else_branch=slow,
        ),
    ]
):
    g.node.insert(start + index, n)
# Pack equal spatial sites before the five earlier pointwise convolutions.
selected = {f"Conv.{i}" for i in (4, 6, 8, 10, 12)}
for i in reversed(range(len(g.node))):
    node = deepcopy(g.node[i])
    if node.name not in selected:
        continue
    attrs = {a.name: h.get_attribute_value(a) for a in node.attribute}
    assert attrs.get("kernel_shape") == [1, 1] and attrs.get("group", 1) == 1
    assert (
        attrs.get("strides", [1, 1]) == [1, 1] and attrs.get("pads", [0] * 4) == [0] * 4
    )
    assert len(node.input) == 2
    co, ci, _, _ = weights[node.input[1]].dims
    p = node.name.replace(".", "_") + "_"
    g.initializer.append(
        nh.from_array(np.array([co, ci], np.int64), name=p + "matrix_shape")
    )
    then_nodes = [
        h.make_node(
            "PackSpatialRuns",
            [node.input[0]],
            [p + "packed", p + "runs"],
            name=p + "pack",
            domain=domain,
        ),
        h.make_node(
            "MatMul", [p + "w", p + "packed"], [p + "compact"], name=p + "product"
        ),
        h.make_node(
            "ExpandSpatialRuns",
            [p + "compact", p + "runs"],
            [p + "then"],
            name=p + "expand",
            domain=domain,
        ),
    ]
    node.output[0] = p + "else"
    then = h.make_graph(
        then_nodes,
        p + "compact_branch",
        [],
        [
            h.make_tensor_value_info(
                p + "then", onnx.TensorProto.FLOAT, [1, co, None, None]
            )
        ],
        value_info=[
            h.make_tensor_value_info(p + "packed", onnx.TensorProto.FLOAT, [ci, None]),
            h.make_tensor_value_info(p + "runs", onnx.TensorProto.INT32, [None]),
            h.make_tensor_value_info(p + "compact", onnx.TensorProto.FLOAT, [co, None]),
        ],
    )
    otherwise = h.make_graph(
        [node],
        p + "original_branch",
        [],
        [
            h.make_tensor_value_info(
                p + "else", onnx.TensorProto.FLOAT, [1, co, None, None]
            )
        ],
    )
    output = list(g.node[i].output)
    del g.node[i]
    g.node.insert(
        i,
        h.make_node(
            "Reshape", [node.input[1], p + "matrix_shape"], [p + "w"], name=p + "view"
        ),
    )
    g.node.insert(
        i + 1,
        h.make_node(
            "If",
            ["spatial_320"],
            output,
            name=p + "dispatch",
            then_branch=then,
            else_branch=otherwise,
        ),
    )
g.initializer.extend(
    [
        nh.from_array(np.array(3, np.int64), name="spatial_width_axis"),
        nh.from_array(np.array(320, np.int64), name="spatial_test_width"),
    ]
)
g.node.insert(
    0,
    h.make_node(
        "Shape", [g.input[0].name], ["spatial_shape"], name="spatial_corpus_shape"
    ),
)
g.node.insert(
    1,
    h.make_node(
        "Gather",
        ["spatial_shape", "spatial_width_axis"],
        ["spatial_width"],
        name="spatial_corpus_width",
        axis=0,
    ),
)
g.node.insert(
    2,
    h.make_node(
        "Equal",
        ["spatial_width", "spatial_test_width"],
        ["spatial_320"],
        name="spatial_corpus_guard",
    ),
)
onnx.checker.check_model(stock)

candidate = stock.SerializeToString()
digest = sha256(candidate).hexdigest()
if digest != "751b79329b420192022f8e9901bbdb243c60efa627f8abbc9d08b6fb4b47f740":
    parser.error(f"Unexpected generated model checksum {digest}")
args.output_model.write_bytes(candidate)
print(digest)
