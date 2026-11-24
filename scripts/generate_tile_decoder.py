#!/usr/bin/env python3
# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Generate the row/column tile decoder layouts for the bundled v4 graph."""
import argparse
from base64 import b85encode
from hashlib import sha256
import struct
from textwrap import wrap
import zlib

import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as s

from generate_compact_decoder import ROOT, _short_arena, buffer_ranges
from sheet_music_recognition._tile_decoder import CORE_WINDOW, TIGHT_WINDOW

TARGET = 272
MODES = ("rows", "columns", "joint")


def tile_graph(source, mode, column_start, target=TARGET):
    model=s.ModelT.InitFromObj(s.Model.GetRootAsModel(source,0))
    sub=model.subgraphs[0]
    old = list(sub.operators)
    axes = {"rows": (1,), "columns": (2,), "joint": (1, 2)}[mode]
    core = target == CORE_WINDOW
    assert len(old) == 59 and (not core or mode == "joint")
    out=[]
    code=s.OperatorCodeT()
    code.builtinCode=code.deprecatedBuiltinCode=s.BuiltinOperator.GATHER
    model.operatorCodes.append(code)
    code_index=len(model.operatorCodes)-1

    def tensor(name, shape, dtype, content=None):
        t=s.TensorT()
        t.name=name.encode()
        t.shape=np.array(shape,np.int32)
        t.type=dtype
        t.hasRank=True
        if content is not None:
            buf=s.BufferT()
            buf.data=np.array(content,dtype="<i4").view(np.uint8)
            model.buffers.append(buf)
            t.buffer=len(model.buffers)-1
        sub.tensors.append(t)
        return len(sub.tensors)-1

    def crop(index, extent, start=None, *, dynamic_rows=True):
        for axis in axes:
            shape=[int(dim) for dim in sub.tensors[index].shape]
            if shape[axis]==extent:
                continue
            assert shape[0]==1 and shape[axis]>extent
            first = column_start // (target // extent) if start is None else start
            dynamic = axis == 1 and dynamic_rows
            values = None if dynamic else range(first, first + extent)
            ids=tensor(f"joint-{axis}-{len(out)}",[extent],s.TensorType.INT32,values)
            if dynamic:
                sub.inputs=np.append(sub.inputs,np.int32(ids))
            shape[axis]=extent
            result=tensor(f"output-{len(out)}",shape,s.TensorType.FLOAT32)
            gather=s.OperatorT()
            gather.opcodeIndex=code_index
            gather.inputs=np.array([index,ids],np.int32)
            gather.outputs=np.array([result],np.int32)
            gather.builtinOptionsType=s.BuiltinOptions.GatherOptions
            gather.builtinOptions=s.GatherOptionsT()
            gather.builtinOptions.axis=axis
            out.append(gather)
            index=result
        return index

    # Each pair of valid convolutions consumes the halo for that stage. The
    # next resize reads only the remaining support for the 192-pixel core.
    sizes = {45: 56, 49: 104, 53: 196} if core else {45: target // 4, 49: target // 2, 53: target}
    resize_origins = {45: column_start // 8, 49: 0, 53: 1}
    skip_origins = {46: column_start // 4, 50: column_start // 2 + 4, 54: column_start + 14}
    needed=0
    for index,op in enumerate(old):
        op.inputs=op.inputs.copy()
        if index in sizes:
            assert model.operatorCodes[op.opcodeIndex].builtinCode==s.BuiltinOperator.RESIZE_NEAREST_NEIGHBOR
            if core:
                assert not op.builtinOptions.alignCorners and op.builtinOptions.halfPixelCenters
            needed=sizes[index]
            op.inputs[0] = crop(op.inputs[0], needed // 2, resize_origins[index] if core else None,
                                dynamic_rows=not core or index == 45)
            op.inputs[1]=tensor(f"upsample-{needed}", [2], s.TensorType.INT32,
                                  [needed if axis in axes else needed * 320 // target for axis in (1, 2)])
        elif index-1 in sizes:
            assert model.operatorCodes[op.opcodeIndex].builtinCode==s.BuiltinOperator.CONCATENATION
            op.inputs[1] = crop(op.inputs[1], needed, skip_origins[index] if core else None)
        if core and index in (47, 48, 51, 52, 55, 56):
            assert model.operatorCodes[op.opcodeIndex].builtinCode == s.BuiltinOperator.CONV_2D
            opts = op.builtinOptions
            assert (opts.strideH, opts.strideW, opts.dilationHFactor, opts.dilationWFactor) == (1, 1, 1, 1)
            assert opts.padding == s.Padding.SAME and sub.tensors[op.inputs[1]].shape[1:3].tolist() == [3, 3]
            opts.padding = s.Padding.VALID
            needed -= 2
        if needed:
            for output in op.outputs:
                t=sub.tensors[output]
                t.shape=t.shape.copy()
                for axis in axes:
                    t.shape[axis] = needed
                if t.shapeSignature is not None:
                    t.shapeSignature=t.shapeSignature.copy()
                    for axis in axes:
                        t.shapeSignature[axis] = needed
        out.append(op)
    sub.operators=out
    builder=flatbuffers.Builder(len(source))
    builder.Finish(model.Pack(builder),file_identifier=b"TFL3")
    return bytes(builder.Output())

def encode_layout(source, mode, target=TARGET):
    full = tile_graph(source, mode, 0, target)
    second = tile_graph(source, mode, 8, target) if mode != "rows" else full
    initial = np.frombuffer(full, dtype="<i4").astype(np.int64)
    delta = np.frombuffer(second, dtype="<i4") - initial
    assert set(map(int, np.unique(delta))) <= {0, 1, 2, 4, 8}
    changed = list(map(int, np.flatnonzero(delta)))
    # The only changes are the contiguous indices in each column gather.
    groups = []
    for index in changed:
        step = int(delta[index])
        if groups and groups[-1][0] + groups[-1][1] == index and groups[-1][2] == step:
            groups[-1][1] += 1
        else:
            groups.append([index, 1, step])

    weights = {key: (offset, count) for key, offset, count in buffer_ranges(source)}
    spans = sorted((offset, *weights[key]) for key, offset, count in buffer_ranges(full)
                   if key in weights and weights[key][1] == count)
    pieces, fields = [], []
    skeleton = bytearray()
    cursor = field = 0
    for end, start, count in [*spans, (len(full), 0, 0)]:
        assert cursor <= end
        base = len(skeleton)
        if cursor < end:
            skeleton.extend(full[cursor:end])
            pieces.append((0, base, len(skeleton)))
        while field < len(groups) and 4 * groups[field][0] < end:
            index, length, step = groups[field]
            first = 4 * index
            assert cursor <= first and first + 4 * length <= end
            origin = int(initial[index])
            assert list(initial[index:index + length]) == list(range(origin, origin + length))
            fields.append((base + first - cursor, length, step))
            field += 1
        if count:
            assert full[end:end+count] == source[start:start+count]
            pieces.append((1, start, start+count))
        cursor = end + count
    assert field == len(groups)

    nominal = TIGHT_WINDOW if target == CORE_WINDOW else target
    for offset in (0,) if mode == "rows" else range(0, 321 - nominal, 8):
        template = bytearray(skeleton)
        for position, length, step in fields:
            origin = struct.unpack_from("<i", template, position)[0]
            start = origin + offset // 8 * step
            struct.pack_into(f"<{length}i", template, position, *range(start, start+length))
        regions = memoryview(template), memoryview(source)
        layout = b"".join(regions[index][start:end] for index, start, end in pieces)
        assert layout == tile_graph(source, mode, offset, target), (mode, offset)

    return b85encode(zlib.compress(skeleton, 9)).decode("ascii"), pieces, fields


def generate(source):
    lines = [
        "# Copyright 2026 Luckolite",
        "# SPDX-License-Identifier: Apache-2.0",
        '"""Generated by scripts/generate_tile_decoder.py from the bundled v4 model."""',
        "",
        "LAYOUTS = {",
    ]
    for mode, target in [*((mode, TARGET) for mode in MODES), ("joint", TIGHT_WINDOW), ("joint", CORE_WINDOW)]:
        skeleton, pieces, fields = encode_layout(source, mode, target)
        key = mode if target == TARGET else f"{mode}{target}"
        lines.extend([f"    {key!r}: (", "        (",
                      *(f"            {line.encode()!r}"
                        for line in wrap(skeleton, 88, break_on_hyphens=False)),
                      "        ),"])
        for rows in (pieces, fields):
            lines.extend(["        (", *(f"            {row!r}," for row in rows), "        ),"])
        lines.append("    ),")
    return "\n".join([*lines, "}", ""]).encode("ascii")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify the generated source")
    args = parser.parse_args()
    source = (ROOT / "models/music_sheets_v4_float16.tflite").read_bytes()
    if sha256(source).hexdigest() != _short_arena.MODEL_SHA256:
        raise SystemExit("Bundled model checksum mismatch")
    output = ROOT / "src/sheet_music_recognition/_tile_decoder_layout.py"
    content = generate(source)
    if args.check:
        if output.read_bytes().replace(b"\r\n", b"\n") != content:
            raise SystemExit("Tile decoder layout needs regeneration")
    else:
        output.write_bytes(content)


if __name__ == "__main__":
    main()
