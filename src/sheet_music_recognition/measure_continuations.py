# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Guide303 framing for two physical regions that form one printed measure."""
import struct

MAGIC = 0x4D434E31  # MCN1
MAX_SPAN = 128 * 10080


def encode(rows, measures, guide_version):
    if not isinstance(rows, list) or len(rows) > len(measures) // 2:
        raise ValueError("Invalid printed measure continuation count")
    if guide_version < 303:
        if rows:
            raise ValueError("Printed measure continuations require guide303")
        return b""
    packed = bytearray(struct.pack(">iii", MAGIC, 4 + 12 * len(rows), len(rows)))
    previous = -1
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("Invalid printed measure continuation")
        second, first_ticks, second_ticks = (
            row.get(name) for name in ("secondMeasureIndex", "firstTicks", "secondTicks"))
        if (any(type(n) is not int for n in (second, first_ticks, second_ticks))
                or not (previous + 1 < second < len(measures))
                or first_ticks <= 0 or second_ticks <= 0 or first_ticks + second_ticks > MAX_SPAN):
            raise ValueError("Invalid printed measure continuation")
        left, right = measures[second - 1:second + 1]
        a, b = left.get("bottom"), right.get("top")
        if (type(a) not in (int, float) or type(b) not in (int, float) or not 0 <= a < b <= 1
                or left.get("cadenceQuarterBeats", 0) or right.get("cadenceQuarterBeats", 0)):
            raise ValueError("Invalid printed measure continuation ownership")
        packed.extend(struct.pack(">iii", second, first_ticks, second_ticks))
        previous = second
    return bytes(packed)

def decode(frame, measures):
    if len(frame) < 12:
        raise ValueError("Truncated printed measure continuation frame")
    magic, size, count = struct.unpack_from(">iii", frame)
    if (magic != MAGIC or count < 0 or count > len(measures) // 2
            or size != 4 + 12 * count or len(frame) != 8 + size):
        raise ValueError("Invalid printed measure continuation frame")
    keys = ("secondMeasureIndex", "firstTicks", "secondTicks")
    rows = [dict(zip(keys, fields)) for fields in struct.iter_unpack(">iii", frame[12:])]
    encode(rows, measures, 303)
    return rows
