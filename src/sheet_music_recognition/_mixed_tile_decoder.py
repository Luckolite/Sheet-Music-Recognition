# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Run two decoder windows while writing only the final owners of each tile."""
from dataclasses import dataclass

import numpy as np

from . import _tile_decoder as decoder
from . import reader

_MIN_NARROW_JOBS = 32


@dataclass(slots=True)
class _Job:
    members: list
    partner: int | None = None
    top: int | None = None


def _placements(spans, window):
    """Choose a shared column and a row offset for every eligible job."""
    offsets = range(0, 321 - decoder.TIGHT_WINDOW, 8)
    available = [(next((offset for offset in offsets if decoder._fits(offset, row, window)), None), column)
                 for row, column in spans]
    left = max(offsets, key=lambda offset: sum(
        top is not None and decoder._fits(offset, column, window) for top, column in available))
    placements = [top if top is not None and decoder._fits(left, column, window) else None
                  for top, column in available]
    return left, placements, sum(top is not None for top in placements)


def _plan_page(gray, edge, rows, cols):
    h, w = gray.shape
    plan = decoder.select(gray, rows, cols, edge)
    if edge is not reader._tile_edges() or (plan is not None and plan.mode != "joint"):
        return None
    regions = [(row, column) for row in rows for column in cols]
    if plan is None:
        # The full graph covers jobs for which no compact window can prove every owner.
        decisions = list(reader._repeated_tile_plan(gray, rows, cols))
        pairs, _ = reader._sparse_pairs(gray, regions, decisions)
    else:
        decisions, pairs = plan.decisions, plan.pairs
    followers = {second for second, *_ in pairs.values()}
    axis = edge.max(axis=0)
    profiles = (decoder._axis_profile(h, rows, axis),
                decoder._axis_profile(w, cols, axis), edge)
    jobs = []
    repeated = {}
    for index, (region, (key, keep)) in enumerate(zip(regions, decisions)):
        if index in followers:
            continue
        gray_tile = gray[region]
        earlier = repeated.pop(key, None)
        if gray_tile[0, 0] == 255 and gray_tile.min() == 255:
            continue
        if index in pairs:
            second, first_box, second_box = pairs[index]
            jobs.append(_Job([(index, first_box), (second, second_box)], second))
            continue
        if earlier is not None and np.array_equal(gray_tile, gray[regions[earlier.members[0][0]]]):
            earlier.members.append((index, earlier.members[0][1]))
            if keep:
                repeated[key] = earlier
            continue
        ink = gray_tile != 255
        bounds = reader._affected_interval(ink.any(axis=1)), reader._affected_interval(ink.any(axis=0))
        job = _Job([(index, bounds)])
        jobs.append(job)
        if keep:
            repeated[key] = job
    jobs_with_owners = []
    spans = []
    for job in jobs:
        row_span = column_span = None
        for index, box in job.members:
            owned_rows, owned_columns = decoder._owned_spans(gray, profiles, regions[index], box)
            row_span = decoder._union(row_span, owned_rows)
            column_span = decoder._union(column_span, owned_columns)
        if row_span is None:
            continue
        jobs_with_owners.append(job)
        spans.append((row_span, column_span))
    # A second arena pays off only when enough predictions use the smaller window.
    window = decoder.TIGHT_WINDOW
    left, tops, count = _placements(spans, window)
    if count < _MIN_NARROW_JOBS:
        return None
    core_left, core_tops, core_count = _placements(spans, decoder.CORE_WINDOW)
    # Use the core when at most 5% of narrow predictions would need the wider graph.
    if core_count >= _MIN_NARROW_JOBS and 20 * core_count >= 19 * count:
        window, left, tops = decoder.CORE_WINDOW, core_left, core_tops
    small, large = [], []
    for job, top in zip(jobs_with_owners, tops, strict=True):
        if top is None:
            job.top = plan.rows[job.members[0][0]] if plan is not None else 0
            large.append(job)
        else:
            job.top = top
            small.append(job)
    return regions, profiles, small, large, left, None if plan is None else plan.column, window


def _copy_owned(labels, prediction, profiles, region, box, top, left, window):
    row, col = region
    y, x = box
    y = slice(max(y.start, top), min(y.stop, top + window))
    x = slice(max(x.start, left), min(x.stop, left + window))
    if y.start >= y.stop or x.start >= x.stop:
        return
    # Final owners let the two shape groups run in either order.
    owned = decoder._owned_mask(profiles, region, y, x)
    local_y = slice(y.start - top, y.stop - top)
    local_x = slice(x.start - left, x.stop - left)
    np.copyto(labels[row, col][y, x], prediction[local_y, local_x], where=owned, casting="unsafe")


def _run_jobs(runtime, gray, labels, regions, profiles, jobs, left, window):
    inputs = runtime.get_input_details()
    target = runtime.get_output_details()[0]
    assert tuple(target["shape"]) == (1, window, window)
    input_buffer = runtime.tensor(inputs[0]["index"])
    output_buffer = runtime.tensor(target["index"])
    previous = None
    ports = decoder.row_ports(window) if window < 320 else ()
    inset = decoder.CORE_INSET if window == decoder.CORE_WINDOW else 0
    overlap_supported = window < 320 and getattr(runtime, "supports_encoder_overlap", False)
    reuse = False
    for at, job in enumerate(jobs):
        index = job.members[0][0]
        tile = gray[regions[index]]
        if job.partner is not None:
            tile = np.minimum(tile, gray[regions[job.partner]])
        if previous != job.top:
            previous = job.top
            for detail, (size, scale, offset) in zip(inputs[1:], ports, strict=True):
                start = job.top // scale + offset
                runtime.set_tensor(detail["index"], np.arange(start, start + size, dtype=np.int32))
        slot = input_buffer()
        slot[0, 0] = tile
        del slot
        if overlap_supported:
            save = False
            if at + 1 < len(jobs) and job.partner is None and jobs[at + 1].partner is None:
                row, column = regions[index]
                following_row, following_column = regions[jobs[at + 1].members[0][0]]
                # The encoder stride is aligned only for consecutive horizontal tiles.
                save = (row.start == following_row.start
                        and following_column.start - column.start == 192)
            runtime.set_encoder_overlap(reuse, save)
            reuse = save
        runtime.invoke()
        result = output_buffer()[0]
        if result.min() < 0 or result.max() > 5:
            raise ValueError("Model returned classes outside 0..5")
        for owner, box in job.members:
            _copy_owned(labels, result, profiles, regions[owner], box,
                        job.top + inset, left + inset, window)
        del result


def predict(engine, gray, rows, columns, path, previous):
    plan = _plan_page(gray, engine.edge, rows, columns)
    if plan is None:
        return None
    regions, profiles, small, large, left, wide_left, narrow_window = plan
    labels = np.zeros_like(gray)
    phases = [(narrow_window, left, small)]
    if large:
        phases.append((320 if wide_left is None else decoder.WINDOW, wide_left, large))
    if previous:
        phases.sort(key=lambda phase: (path, phase[0], phase[1]) != previous)

    # Do not retain a second arena even when the larger shape is needed.
    try:
        for window, column, jobs in phases:
            recipe = (path, window, column)
            if previous != recipe:
                if hasattr(engine, "runtime"):
                    del engine.runtime
                try:
                    if window == 320:
                        engine._initialize_model(path, True)
                        initialized = True
                    else:
                        selection = decoder.Selection("joint", {}, column, [], {}, set(), window)
                        initialized = engine._initialize_tile_decoder(path, selection)
                except MemoryError:
                    initialized = False
                if not initialized:
                    _reset(engine, path)
                    return None
            _run_jobs(engine.runtime, gray, labels, regions, profiles, jobs, column or 0, window)
            previous = recipe
    except Exception:
        _reset(engine, path)
        raise
    engine._pending_model = None
    engine._tile_layout = (path, "mixed", previous[2])
    engine._mixed_tile_recipe = previous
    return labels


def _reset(engine, path):
    if hasattr(engine, "runtime"):
        del engine.runtime
    engine._pending_model = (path, True)
    engine._tile_layout = engine._mixed_tile_recipe = None
