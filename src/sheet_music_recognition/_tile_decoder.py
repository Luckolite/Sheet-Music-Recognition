# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Plan bounded decoder windows on the checksum-pinned v4 tile grid."""
from base64 import b85decode
import struct
from typing import NamedTuple
import zlib

import numpy as np

WINDOW = 272
MAX_PIXELS = 6_000_000
_OFFSETS = range(0, 321 - WINDOW, 8)
# Three decoder stages have two 3x3 convolutions each. Sixteen rows or
# columns cover their combined halo after the encoder has run in full.
_HALO = 16


class Selection(NamedTuple):
    mode: str
    rows: dict | None
    column: int | None
    decisions: list
    pairs: dict
    blank_tiles: set


def _fits(offset, span):
    if span is None:
        return True
    start = offset if offset == 0 else offset + _HALO
    end = offset + WINDOW
    if end != 320:
        end -= _HALO
    return start <= span[0] and span[1] <= end


def _union(first, second):
    if first is None:
        return second
    if second is None:
        return first
    return min(first[0], second[0]), max(first[1], second[1])


def _owned_spans(gray, owner, region, index, box):
    from .reader import _affected_interval

    if box is None:
        ink = gray[region] != 255
        rows = _affected_interval(ink.any(axis=1))
        if rows is None:
            return None, None
        columns = _affected_interval(ink.any(axis=0))
    else:
        rows, columns = box
    visible = owner[region][rows, columns] == index
    retained_rows = np.flatnonzero(visible.any(axis=1))
    if not retained_rows.size:
        return None, None
    retained_columns = np.flatnonzero(visible.any(axis=0))
    return ((rows.start + int(retained_rows[0]), rows.start + int(retained_rows[-1]) + 1),
            (columns.start + int(retained_columns[0]), columns.start + int(retained_columns[-1]) + 1))


def select(gray, rows, columns, edge, *, active_mode=None, active_column=None):
    """Cover every affected final owner, including all recipients of a cached tile."""
    from .reader import _repeated_tile_plan, _sparse_pairs, _tile_windows

    if min(gray.shape) < 320 or gray.size > MAX_PIXELS or not 3 <= len(rows) * len(columns) < 255:
        return None
    regions = [(row, column) for row in rows for column in columns]
    decisions = list(_repeated_tile_plan(gray, rows, columns))
    pairs, blank_tiles = _sparse_pairs(gray, regions, decisions)
    followers = {partner for partner, *_ in pairs.values()}
    owner = np.empty(gray.shape, dtype=np.uint8)
    for index, (region, wins) in enumerate(_tile_windows(*gray.shape, edge, rows, columns)):
        # Later writes win the same ties as the ordinary prediction loop.
        np.copyto(owner[region], index, where=wins)

    primary, repeated = {}, {}
    for index, (region, (key, _)) in enumerate(zip(regions, decisions)):
        if index in followers:
            continue
        if index in pairs:
            partner, first, second = pairs[index]
            a, b = _owned_spans(gray, owner, region, index, first)
            c, d = _owned_spans(gray, owner, regions[partner], partner, second)
            primary[index] = _union(a, c), _union(b, d)
        else:
            row_span, column_span = primary[index] = _owned_spans(gray, owner, region, index, None)
            if key is not None:
                a, b = repeated.get(key, (None, None))
                repeated[key] = _union(a, row_span), _union(b, column_span)

    # The planning key can collide. Covering its whole union is conservative,
    # and the prediction cache still compares complete input bytes on reuse.
    options = {active_column} if active_column is not None else set(_OFFSETS)
    offsets = {}
    rows_fit = active_mode != "columns"
    for index, (row_span, column_span) in primary.items():
        key, _ = decisions[index]
        if index not in pairs and key is not None:
            row_span, column_span = repeated[key]
        if rows_fit:
            offset = next((offset for offset in _OFFSETS if _fits(offset, row_span)), None)
            if offset is None:
                rows_fit = False
            else:
                offsets[index] = offset
        options = {offset for offset in options if _fits(offset, column_span)}
        if not rows_fit and not options:
            return None

    column = min(options) if options and active_mode != "rows" else None
    if rows_fit:
        mode = "rows" if column is None else "joint"
    elif column is not None:
        mode = "columns"
    else:
        return None
    if active_mode is not None and mode != active_mode:
        return None
    return Selection(mode, offsets if rows_fit else None, column, decisions, pairs, blank_tiles)


def specialize(content, selection):
    """Reassemble a generated graph from the already verified v4 source bytes."""
    from ._tile_decoder_layout import LAYOUTS

    packed, pieces, fields = LAYOUTS[selection.mode]
    try:
        skeleton = bytearray(zlib.decompress(b85decode(packed)))
    except zlib.error as error:
        raise ValueError("Invalid tile decoder layout") from error
    for position, count, step in fields:
        start = selection.column // 8 * step
        struct.pack_into(f"<{count}i", skeleton, position, *range(start, start + count))
    sources = memoryview(skeleton), memoryview(content)
    return b"".join(sources[index][first:last] for index, first, last in pieces)
