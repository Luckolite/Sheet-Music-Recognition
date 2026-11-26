# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Generate the optional exact-affine detector from its pinned upstream graph."""

import argparse
from collections import defaultdict
from hashlib import sha256
from pathlib import Path

import onnx
from onnx import TensorProto, helper

STOCK_SHA = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
FUSED_SHA = "60a49903b55f3333ffe3bfc9b52fc93d791a4b9bf33d7373387924abfd18f1d4"
DOMAIN = "com.luckolite.reader"


def consumers(graph):
    result = defaultdict(list)
    for index, node in enumerate(graph.node):
        for name in node.input:
            result[name].append(index)
    return result


def fuse_affine(model):
    graph = model.graph
    uses = consumers(graph)
    outputs = {value.name for value in graph.output}
    initializers = {value.name: value for value in graph.initializer}

    def scalar(name, suffix):
        value = initializers.get(name)
        return (value is not None and name.startswith("learnable_affine_block_")
                and name.endswith(suffix) and value.data_type == TensorProto.FLOAT
                and list(value.dims) == [1])

    replace, remove = {}, set()
    for index, node in enumerate(graph.node):
        if node.domain or node.op_type != "Mul" or len(node.input) != 2:
            continue
        data, factor = node.input
        if scalar(data, ".w_0"):
            data, factor = factor, data
        if not scalar(factor, ".w_0"):
            continue

        value = node.output[0]
        aliases = []
        while value not in outputs and len(uses[value]) == 1:
            alias_index = uses[value][0]
            alias = graph.node[alias_index]
            if alias.domain or alias.op_type != "Identity":
                break
            aliases.append(alias_index)
            value = alias.output[0]
        if value in outputs or len(uses[value]) != 1:
            continue

        add_index = uses[value][0]
        add = graph.node[add_index]
        if add.domain or add.op_type != "Add" or len(add.input) != 2:
            continue
        offset = add.input[1] if add.input[0] == value else add.input[0]
        if not scalar(offset, ".w_1") or factor[:-1] != offset[:-1]:
            continue
        replace[add_index] = helper.make_node(
            "ExactAffine", [data, factor, offset], list(add.output),
            name=add.name + "_exact_affine", domain=DOMAIN,
        )
        remove.add(index)
        remove.update(aliases)

    if len(replace) != 52:
        raise ValueError("Expected 52 single-use learned affine pairs")
    nodes = [replace.get(index, node) for index, node in enumerate(graph.node)
             if index not in remove]
    removed_values = {graph.node[index].output[0] for index in remove}
    metadata = [value for value in graph.value_info if value.name not in removed_values]
    del graph.node[:]
    graph.node.extend(nodes)
    del graph.value_info[:]
    graph.value_info.extend(metadata)
    model.opset_import.append(helper.make_opsetid(DOMAIN, 1))


def fuse_activation(graph):
    producers = {name: index for index, node in enumerate(graph.node) for name in node.output}
    uses = consumers(graph)
    outputs = {value.name for value in graph.output}
    remove = set()
    for node in graph.node:
        if node.domain != DOMAIN or node.op_type != "ExactAffine":
            continue
        value = node.input[0]
        index = producers.get(value)
        if index is None or value in outputs or len(uses[value]) != 1:
            continue
        activation = graph.node[index]
        if activation.domain or activation.op_type != "HardSwish" or activation.attribute:
            continue
        remove.add(index)
        node.input[0] = activation.input[0]
        node.op_type = "ExactActivatedAffine"

    if len(remove) != 24:
        raise ValueError("Expected 24 single-use HardSwish activations")
    for index in sorted(remove, reverse=True):
        del graph.node[index]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("detector", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    raw = args.detector.read_bytes()
    if sha256(raw).hexdigest() != STOCK_SHA:
        parser.error("Unexpected detector checksum")
    model = onnx.load_model_from_string(raw)
    fuse_affine(model)
    fuse_activation(model.graph)
    onnx.checker.check_model(model)
    artifact = model.SerializeToString()
    result_sha = sha256(artifact).hexdigest()
    if result_sha != FUSED_SHA:
        parser.error(f"Unexpected fused detector checksum: {result_sha}")
    args.output.write_bytes(artifact)
    print(result_sha)


if __name__ == "__main__":
    main()
