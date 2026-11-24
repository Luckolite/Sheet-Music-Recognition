# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Generate exact native LiteRT model recipes using the bundled v4 model."""
from base64 import b85encode
from hashlib import sha256
import json
from pathlib import Path
import sys
from zlib import compress

from ai_edge_litert import schema_py_generated as schema
import flatbuffers
import numpy as np

MODEL = "music_sheets_v4_float16.tflite"
MODEL_SHA256 = "92ab7c34c27cb704d95f0cde247b3611d1563fdf1c114b9f9817abb18a7b6a93"
LAYERS = (35, 37, 38, 40, 41, 43, 44, 47, 48, 51, 52, 55, 56)
FLOAT_TYPES = {schema.TensorType.FLOAT16: "<f2", schema.TensorType.FLOAT32: "<f4"}
DETAIL_TYPES = {schema.TensorType.INT32: "int32", schema.TensorType.INT64: "int64",
                schema.TensorType.FLOAT32: "float32"}


def flat_model(data):
    return schema.Model.GetRootAsModel(data, 0)


def span(model, index):
    buffer = model.Buffers(index)
    field = buffer._tab.Offset(4)
    return (buffer._tab.Vector(field), buffer.DataLength()) if field else (0, 0)


def weight_layout(raw):
    packed = flat_model(raw)
    model = schema.ModelT.InitFromObj(packed)
    graph = model.subgraphs[0]
    producers = {int(t): node for node in graph.operators for t in node.outputs}

    def constant(index):
        node = producers.get(index)
        if node is not None:
            assert model.operatorCodes[node.opcodeIndex].builtinCode == schema.BuiltinOperator.DEQUANTIZE
            index = int(node.inputs[0])
        tensor = graph.tensors[index]
        position, count = span(packed, tensor.buffer)
        dtype = FLOAT_TYPES[tensor.type]
        assert count == int(np.prod(tensor.shape)) * np.dtype(dtype).itemsize
        return position, dtype, tensor.shape.tolist()

    weights = {}
    for i in LAYERS:
        node = graph.operators[i]
        inputs = tuple(map(int, node.inputs))
        weight, bias = map(constant, inputs[1:])
        oc, kh, kw, ic = weight[2]
        assert (kh, kw) == (3, 3) and oc % 16 == 0 and ic % 8 == 0
        weights[int(node.outputs[0])] = inputs, weight, bias, ic, oc, node.builtinOptions.dilationHFactor
    return weights


def fuse_decoder_merges(model):
    """Fold exact 2x resizes and contiguous skip crops into one copy per decoder stage."""
    graph = model.subgraphs[0]
    producers = {int(port): node for node in graph.operators for port in node.outputs}

    def kind(node):
        return model.operatorCodes[node.opcodeIndex].builtinCode

    removals = set()
    metadata = {}
    for node in graph.operators:
        if kind(node) != schema.BuiltinOperator.CONCATENATION or node.builtinOptions.axis not in (3, -1):
            continue
        upsampled, skip = map(int, node.inputs)
        resize = producers[upsampled]
        assert kind(resize) == schema.BuiltinOperator.RESIZE_NEAREST_NEIGHBOR
        assert resize.builtinOptions.halfPixelCenters and not resize.builtinOptions.alignCorners
        assert node.builtinOptions.fusedActivationFunction == schema.ActivationFunctionType.NONE
        resized, output = int(resize.inputs[0]), int(node.outputs[0])
        output_shape = graph.tensors[output].shape.tolist()
        _, height, width, channels = output_shape
        assert height % 2 == width % 2 == 0
        assert graph.tensors[resized].shape.tolist() == [1, height // 2, width // 2, 2 * channels // 3]
        assert graph.tensors[skip].shape.tolist() == [1, height, width, channels // 3]
        ports = []
        crops = []

        def describe_crop(port):
            desired = graph.tensors[port].shape.tolist()
            coordinates = [[0, -1, desired[1]], [0, -1, desired[2]]]
            while port in producers and kind(producers[port]) == schema.BuiltinOperator.GATHER:
                gather = producers[port]
                port, indices = map(int, gather.inputs)
                axis = gather.builtinOptions.axis
                assert axis in (1, 2)
                tensor = graph.tensors[indices]
                assert tensor.type == schema.TensorType.INT32 and tensor.shape.tolist() == [desired[axis]]
                if tensor.buffer and model.buffers[tensor.buffer].data is not None:
                    values = np.frombuffer(model.buffers[tensor.buffer].data, dtype="<i4")
                    first = int(values[0])
                    assert np.array_equal(values, np.arange(first, first + desired[axis]))
                    coordinates[axis - 1][0] = first
                else:
                    assert axis == 1 and indices in graph.inputs
                    coordinates[axis - 1][1] = len(ports)
                    ports.append(indices)
                removals.add(id(gather))
            physical = graph.tensors[port].shape.tolist()
            assert physical[0] == 1 and physical[-1] == desired[-1]
            assert graph.tensors[port].type == schema.TensorType.FLOAT32
            for i, (offset, _, size) in enumerate(coordinates, 1):
                assert offset >= 0 and offset + size <= physical[i]
            crops.append([len(ports), physical, coordinates])
            ports.append(port)

        describe_crop(resized)
        describe_crop(skip)
        name = f"ScoreResizeMerge{output}"
        metadata[name] = [output_shape, crops]
        code = schema.OperatorCodeT()
        code.builtinCode = code.deprecatedBuiltinCode = schema.BuiltinOperator.CUSTOM
        code.customCode = name.encode()
        node.opcodeIndex = len(model.operatorCodes)
        model.operatorCodes.append(code)
        node.inputs = np.array(ports, dtype=np.int32)
        node.builtinOptions = None
        node.builtinOptionsType = schema.BuiltinOptions.NONE
        removals.add(id(resize))
    assert len(metadata) == 3
    candidates = {int(out) for node in graph.operators if id(node) in removals for out in node.outputs}
    graph.operators = [node for node in graph.operators if id(node) not in removals]
    assert not candidates.intersection(int(i) for node in graph.operators for i in node.inputs)
    assert not candidates.intersection(int(i) for i in graph.outputs)
    return metadata


def exact_graph(raw, weights):
    model = schema.ModelT.InitFromObj(flat_model(raw))
    graph = model.subgraphs[0]
    producers = {int(t): node for node in graph.operators for t in node.outputs}
    unused = set()
    calls = []
    for tensor, (inputs, _, _, ic, oc, dilation) in weights.items():
        node = producers[tensor]
        opts = node.builtinOptions
        src, dst = graph.tensors[inputs[0]].shape, graph.tensors[tensor].shape
        assert model.operatorCodes[node.opcodeIndex].builtinCode == schema.BuiltinOperator.CONV_2D
        assert opts.fusedActivationFunction == schema.ActivationFunctionType.RELU
        assert opts.padding in (schema.Padding.SAME, schema.Padding.VALID)
        assert opts.strideH == opts.strideW == 1
        assert opts.dilationHFactor == opts.dilationWFactor == dilation
        valid = opts.padding == schema.Padding.VALID
        if tensor in (75, 77):
            assert not valid and ic == 32 and oc == (32 if tensor == 75 else 64) and dilation == 1
        if tensor == 78:
            assert not valid and ic == oc == 64 and dilation == 1
        if tensor in (80, 81):
            assert not valid and ic == (64 if tensor == 80 else 128) and oc == 128 and dilation == 1
        if tensor in (95, 96):
            if not valid:
                continue
            assert ic == (96 if tensor == 95 else 32) and oc == 32 and dilation == 1
        halo = 2 * dilation if valid else 0
        assert tuple(node.inputs) == inputs
        assert tuple(dst) == (1, int(src[1]) - halo, int(src[2]) - halo, oc)
        assert src[0] == 1 and src[-1] == ic
        name = f"ScoreSparse{tensor}"
        call = name, tensor, int(dst[1]), int(dst[2])
        calls.append((*call, True) if valid else call)
        code = schema.OperatorCodeT()
        code.builtinCode = code.deprecatedBuiltinCode = schema.BuiltinOperator.CUSTOM
        code.customCode = name.encode()
        node.opcodeIndex = len(model.operatorCodes)
        node.builtinOptionsType = schema.BuiltinOptions.NONE
        node.builtinOptions = None
        unused.update(inputs[1:])
        node.inputs = node.inputs[:1].copy()
        model.operatorCodes.append(code)

    unused.difference_update(int(t) for node in graph.operators for t in node.inputs)
    graph.operators = [node for node in graph.operators if not any(int(t) in unused for t in node.outputs)]
    merges = fuse_decoder_merges(model)
    builder = flatbuffers.Builder(len(raw))
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")

    def details(indices):
        return [(int(i), graph.tensors[i].shape.tolist(), DETAIL_TYPES[graph.tensors[i].type]) for i in indices]

    return bytes(builder.Output()), calls, details(graph.inputs), details(graph.outputs), merges


def split_model(raw, compiled):
    source, transformed = flat_model(raw), flat_model(compiled)
    shared = []
    for i in range(min(source.BuffersLength(), transformed.BuffersLength())):
        before, length = span(source, i)
        after, count = span(transformed, i)
        if count >= 64 and count == length and compiled[after:after+count] == raw[before:before+count]:
            shared.append((after, before, count))
    cursor = 0
    chunks = []
    skeleton = bytearray()
    for after, before, count in sorted(shared) + [(len(compiled), 0, 0)]:
        assert after >= cursor
        if after > cursor:
            start = len(skeleton)
            skeleton.extend(compiled[cursor:after])
            chunks.append((0, start, len(skeleton)))
        if count:
            chunks.append((1, before, before + count))
        cursor = after + count

    regions = memoryview(skeleton), memoryview(raw)
    assert compiled == b"".join(regions[k][lo:hi] for k, lo, hi in chunks)
    return b85encode(compress(skeleton, 9)).decode("ascii"), chunks


def variants(raw):
    from sheet_music_recognition import _short_arena, _tile_decoder

    yield raw
    for mode, rows, offsets in (("rows", {0: 0}, (None,)), ("columns", None, range(0, 49, 8)),
                                ("joint", {0: 0}, range(0, 49, 8))):
        for x in offsets:
            spec = _tile_decoder.Selection(mode, rows, x, [], {}, set())
            yield _tile_decoder.specialize(raw, spec)
    for window in (_tile_decoder.TIGHT_WINDOW, _tile_decoder.CORE_WINDOW):
        for x in range(0, 321 - _tile_decoder.TIGHT_WINDOW, 8):
            spec = _tile_decoder.Selection("joint", {0: 0}, x, [], {}, set(), window)
            yield _tile_decoder.specialize(raw, spec)
    for height in range(88, 321, 8):
        yield _short_arena.specialize_decoder(raw, height)


def generate(root):
    sys.path.insert(0, str(root))
    package = root / "sheet_music_recognition"
    raw = (package / "data" / MODEL).read_bytes()
    if sha256(raw).hexdigest() != MODEL_SHA256:
        raise ValueError("Bundled model checksum mismatch")
    weights = weight_layout(raw)
    layouts = {}
    for model in variants(raw):
        key = sha256(model).hexdigest()
        if key not in layouts:
            compiled, calls, inputs, outputs, merges = exact_graph(model, weights)
            layouts[key] = (*split_model(raw, compiled), calls, inputs, outputs, merges)
    result = compress(json.dumps([weights, layouts], separators=(",", ":")).encode(), 9)
    (package / "_score_sparse_layouts.json.z").write_bytes(result)
    print(f"Built {len(layouts)} graph recipes using {len(result):,} bytes")


if __name__ == "__main__":
    generate(Path(sys.argv[1]).resolve())
