#!/usr/bin/env python3
# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Regenerate the compact decoder layout using the bundled LiteRT schema."""
import argparse
from base64 import b85encode
from hashlib import sha256
from pathlib import Path
import struct
import sys
from textwrap import wrap
import zlib

import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as s

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
from sheet_music_recognition import _short_arena


def cropped_graph(source, height):
    compact = _short_arena.specialize(source, height)
    model = s.ModelT.InitFromObj(s.Model.GetRootAsModel(compact, 0))
    sub = model.subgraphs[0]
    ops = list(sub.operators)
    stages = (45, 49, 53)
    assert len(ops) == 59
    assert all(model.operatorCodes[ops[i].opcodeIndex].builtinCode ==
               s.BuiltinOperator.RESIZE_NEAREST_NEIGHBOR for i in stages)
    assert all(model.operatorCodes[ops[i+1].opcodeIndex].builtinCode ==
               s.BuiltinOperator.CONCATENATION for i in stages)
    assert all(model.operatorCodes[ops[i].opcodeIndex].builtinCode ==
               s.BuiltinOperator.CONV_2D for i in (47, 48, 51, 52, 55, 56, 57))
    target = height - 80
    code = s.OperatorCodeT()
    code.builtinCode = code.deprecatedBuiltinCode = s.BuiltinOperator.SLICE
    model.operatorCodes.append(code)
    opcode = len(model.operatorCodes)-1

    def const(name, values):
        t = s.TensorT()
        t.name = name.encode()
        t.shape = np.array([len(values)], np.int32)
        t.type = s.TensorType.INT32
        t.hasRank = True
        buf = s.BufferT()
        buf.data = np.array(values, dtype="<i4").view(np.uint8).reshape(-1)
        model.buffers.append(buf)
        t.buffer = len(model.buffers)-1
        sub.tensors.append(t)
        return len(sub.tensors)-1

    zeros = const("slice_origin", [0, 0, 0, 0])
    new_ops = []
    def crop(index, visible):
        before = sub.tensors[index]
        shape = [int(v) for v in before.shape]
        assert before.type == s.TensorType.FLOAT32 and shape[0] == 1
        if shape[1] == visible:
            return index
        assert shape[1] > visible > 0
        shape[1] = visible
        size = const("slice_extent", shape)
        out = s.TensorT()
        out.name = before.name + b"_cropped"
        out.shape = np.array(shape, np.int32)
        out.type = before.type
        out.hasRank = True
        sub.tensors.append(out)
        result = len(sub.tensors)-1
        sliceop = s.OperatorT()
        sliceop.opcodeIndex = opcode
        sliceop.inputs = np.array([index, zeros, size], np.int32)
        sliceop.outputs = np.array([result], np.int32)
        sliceop.builtinOptionsType = s.BuiltinOptions.SliceOptions
        sliceop.builtinOptions = s.SliceOptionsT()
        new_ops.append(sliceop)
        return result

    # Keep the next two rows for the two 3x3 convolutions in each stage.
    # The resize extent stays even so nearest-neighbor still doubles exactly.
    heights = {45: target // 4 + 4, 49: target // 2 + 4, 53: target + 2}
    starts = min(heights)
    height_for_output = None
    for i, op in enumerate(ops):
        op.inputs = op.inputs.copy()
        if i in heights:
            next_height = heights[i]
            assert next_height % 2 == 0
            op.inputs[0] = crop(op.inputs[0], next_height//2)
            old_const = sub.tensors[op.inputs[1]]
            assert old_const.type == s.TensorType.INT32
            dims = model.buffers[old_const.buffer].data.view("<i4")
            assert len(dims) == 2 and int(dims[0]) > next_height
            op.inputs[1] = const("new_nearest_extent", [next_height, int(dims[1])])
            height_for_output = next_height
        elif i-1 in heights:
            op.inputs[1] = crop(op.inputs[1], height_for_output)
        if i >= starts:
            assert height_for_output
            for index in op.outputs:
                tensor = sub.tensors[index]
                assert tensor.shape[0] == 1 and tensor.shape[1] > height_for_output
                tensor.shape = tensor.shape.copy()
                tensor.shape[1] = height_for_output
                if tensor.shapeSignature is not None:
                    assert tensor.shapeSignature[1] > height_for_output
                    tensor.shapeSignature = tensor.shapeSignature.copy()
                    tensor.shapeSignature[1] = height_for_output
        new_ops.append(op)
    sub.operators = new_ops
    buffer = flatbuffers.Builder(len(compact))
    buffer.Finish(model.Pack(buffer), file_identifier=b"TFL3")
    return bytes(buffer.Output())

def buffer_ranges(content):
    model = s.Model.GetRootAsModel(content, 0)
    start = np.frombuffer(content, dtype=np.uint8).ctypes.data
    for index in range(model.BuffersLength()):
        buffer = model.Buffers(index)
        if buffer.DataLength() >= 128:
            data = buffer.DataAsNumpy()
            yield sha256(data).digest(), data.ctypes.data - start, data.size


def generate(source):
    reference = cropped_graph(source, 320)
    upper = np.frombuffer(reference, dtype="<i4").astype(np.int64)
    lower = np.frombuffer(cropped_graph(source, 312), dtype="<i4")
    delta = upper - lower
    fields = [(index * 4, int(upper[index]), int(delta[index]))
              for index in map(int, np.flatnonzero(delta))]
    assert all(step in (1, 2, 4, 8) for _, _, step in fields)
    originals = {key: (offset, count) for key, offset, count in buffer_ranges(source)}
    spans = sorted((offset, *originals[key]) for key, offset, count in buffer_ranges(reference)
                   if key in originals and originals[key][1] == count)
    pieces, patches = [], []
    skeleton = bytearray()
    cursor = field = 0
    for offset, start, length in [*spans, (len(reference), 0, 0)]:
        assert cursor <= offset
        begin = len(skeleton)
        if cursor < offset:
            skeleton.extend(reference[cursor:offset])
            pieces.append((0, begin, len(skeleton)))
        while field < len(fields) and fields[field][0] < offset:
            position, value, step = fields[field]
            assert cursor <= position <= offset - 4
            patches.append((begin + position - cursor, value, step))
            field += 1
        if length:
            assert reference[offset:offset + length] == source[start:start + length]
            pieces.append((1, start, start + length))
        cursor = offset + length
    assert field == len(fields)

    for height in range(88, 321, 8):
        variant = bytearray(skeleton)
        for offset, value, step in patches:
            struct.pack_into("<i", variant, offset, value - (320 - height) // 8 * step)
        sections = memoryview(variant), memoryview(source)
        assembled = b"".join(sections[k][a:b] for k, a, b in pieces)
        assert assembled == cropped_graph(source, height), height

    encoded = b85encode(zlib.compress(skeleton, 9)).decode("ascii")
    lines = [
        "# Copyright 2026 Luckolite",
        "# SPDX-License-Identifier: Apache-2.0",
        '"""Generated by scripts/generate_compact_decoder.py from the bundled v4 model."""',
        "",
        "SKELETON = (",
        *(f"    {part.encode()!r}" for part in wrap(encoded, 95, break_on_hyphens=False)),
        ")",
        "",
    ]
    for name, rows in (("PIECES", pieces), ("HEIGHT_FIELDS", patches)):
        lines.extend([f"{name} = (", *(f"    {row!r}," for row in rows), ")", ""])
    return "\n".join(lines).encode("ascii")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify the generated source")
    args = parser.parse_args()
    original = (ROOT / "models/music_sheets_v4_float16.tflite").read_bytes()
    if sha256(original).hexdigest() != _short_arena.MODEL_SHA256:
        raise SystemExit("Bundled model checksum mismatch")
    generated = generate(original)
    target = ROOT / "src/sheet_music_recognition/_compact_decoder_layout.py"
    if args.check:
        if target.read_bytes().replace(b"\r\n", b"\n") != generated:
            raise SystemExit("Compact decoder layout needs regeneration")
    else:
        target.write_bytes(generated)


if __name__ == "__main__":
    main()
