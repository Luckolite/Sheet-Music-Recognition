# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Plan bounded decoder windows on the checksum-pinned v4 tile grid."""
from base64 import b85decode
import struct
from typing import NamedTuple
import zlib

import numpy as np

WINDOW = 272
TIGHT_WINDOW = 224
CORE_INSET = 16
CORE_WINDOW = TIGHT_WINDOW - 2 * CORE_INSET
MAX_PIXELS = 20_000_000
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
    window: int = WINDOW


def row_ports(window):
    """Return the length, input scale, and inset for each gather."""
    if window == CORE_WINDOW:
        return ((28, 8, 0), (56, 4, 0), (104, 2, 4), (196, 1, 14))
    return tuple((window // scale, scale, 0) for scale in (8, 4, 2, 1))


def _fits(offset, span, window=WINDOW):
    if span is None:
        return True
    if window == CORE_WINDOW:
        start = offset + CORE_INSET
        return start <= span[0] and span[1] <= start + CORE_WINDOW
    start = offset if offset == 0 else offset + _HALO
    end = offset + window
    if end != 320:
        end -= _HALO
    return start <= span[0] and span[1] <= end


def _union(first, second):
    if first is None:
        return second
    if second is None:
        return first
    return min(first[0], second[0]), max(first[1], second[1])


def _axis_profile(size, windows, weights):
    """Keep the best score overall and the best later score for each window."""
    peak = np.zeros(size, dtype=np.uint8)
    later = {}
    for window in reversed(windows):
        current = peak[window]
        later[window.start] = current.copy()
        np.maximum(current, weights, out=current)
    return peak, later


def _owned_mask(profiles, region, rows, columns):
    y, x = region
    vertical, horizontal, edge = profiles
    v, below = vertical
    h, right = horizontal
    # Among the tied winners, a later row or column takes ownership. The
    # other axis always has at least one tile that reaches this threshold.
    threshold = np.minimum.outer(v[y][rows], h[x][columns])
    return ((edge[rows, columns] >= threshold)
               & (below[y.start][rows, None] < threshold)
               & (right[x.start][None, columns] < threshold))


def _owned_spans(gray, profiles, region, box):
    from .reader import _affected_interval

    if box is None:
        ink = gray[region] != 255
        rows = _affected_interval(ink.any(axis=1))
        if rows is None:
            return None, None
        columns = _affected_interval(ink.any(axis=0))
    else:
        rows, columns = box
    visible = _owned_mask(profiles, region, rows, columns)
    retained_rows = np.flatnonzero(visible.any(axis=1))
    if not retained_rows.size:
        return None, None
    retained_columns = np.flatnonzero(visible.any(axis=0))
    return ((rows.start + int(retained_rows[0]), rows.start + int(retained_rows[-1]) + 1),
            (columns.start + int(retained_columns[0]), columns.start + int(retained_columns[-1]) + 1))


def select(gray, rows, columns, edge, *, active_mode=None, active_column=None):
    """Cover every affected final owner, including all recipients of a cached tile."""
    from .reader import _repeated_tile_plan, _sparse_pairs, _tile_edges

    if min(gray.shape) < 320 or gray.size > MAX_PIXELS or len(rows) * len(columns) < 3 or edge is not _tile_edges():
        return None
    regions = [(row, column) for row in rows for column in columns]
    decisions = list(_repeated_tile_plan(gray, rows, columns))
    pairs, blank_tiles = _sparse_pairs(gray, regions, decisions)
    followers = {partner for partner, *_ in pairs.values()}
    axis = edge.max(axis=0)
    profiles = (_axis_profile(gray.shape[0], rows, axis),
                _axis_profile(gray.shape[1], columns, axis), edge)

    primary, repeated = {}, {}
    for index, (region, (key, _)) in enumerate(zip(regions, decisions)):
        if index in followers:
            continue
        if index in pairs:
            partner, first, second = pairs[index]
            a, b = _owned_spans(gray, profiles, region, first)
            c, d = _owned_spans(gray, profiles, regions[partner], second)
            primary[index] = _union(a, c), _union(b, d)
        else:
            row_span, column_span = primary[index] = _owned_spans(gray, profiles, region, None)
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

    key = selection.mode if selection.window == WINDOW else f"{selection.mode}{selection.window}"
    try:
        packed, pieces, fields = LAYOUTS[key]
        skeleton = bytearray(zlib.decompress(b85decode(packed)))
    except (KeyError, zlib.error) as error:
        raise ValueError("Invalid tile decoder layout") from error
    for position, count, step in fields:
        first = struct.unpack_from("<i", skeleton, position)[0]
        start = first + selection.column // 8 * step
        struct.pack_into(f"<{count}i", skeleton, position, *range(start, start + count))
    sources = memoryview(skeleton), memoryview(content)
    return b"".join(sources[index][first:last] for index, first, last in pieces)
