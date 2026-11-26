# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np

from sheet_music_recognition import reader
from sheet_music_recognition.runtime import inference_interpreter


class TileDecoderTest(unittest.TestCase):
    def test_encoder_overlap_is_scoped_to_adjacent_unpaired_jobs(self):
        from sheet_music_recognition import _mixed_tile_decoder as mixed

        origins = [(0, 0), (0, 192), (0, 384), (192, 0), (0, 576), (0, 768),
                   (0, 900), (192, 900), (192, 1092)]
        regions = [(slice(y, y + 320), slice(x, x + 320)) for y, x in origins]
        bounds = (slice(0, 192), slice(0, 192))
        jobs = [mixed._Job([(i, bounds)], top=0) for i in (0, 1, 2, 4, 5, 6, 7, 8)]
        jobs[2].members.append((3, bounds))
        jobs[2].partner = 3
        gray = np.full((512, 1412), 255, dtype=np.uint8)
        input_tile = np.empty((1, 3, 320, 320), dtype=np.float32)
        prediction = np.zeros((1, 192, 192), dtype=np.int32)
        output_index = 1 + len(mixed.decoder.row_ports(192))
        observed = []
        runtime = SimpleNamespace(
            get_input_details=lambda: [{"index": i} for i in range(output_index)],
            get_output_details=lambda: [{"index": output_index, "shape": prediction.shape}],
            tensor=lambda index: (lambda: prediction if index == output_index else input_tile),
            set_tensor=lambda *_: None,
            supports_encoder_overlap=True,
            set_encoder_overlap=lambda reuse, save: observed.append((reuse, save)),
            invoke=lambda: None,
        )
        with patch.object(mixed, "_copy_owned"):
            mixed._run_jobs(runtime, gray, None, regions, None, jobs, 0, 192)
            mixed._run_jobs(runtime, gray, None, regions, None, jobs[-1:], 0, 192)
        self.assertEqual(observed, [
            (False, True), (True, False), (False, False),
            (False, True), (True, False), (False, False),
            (False, True), (True, False), (False, False),
        ])

    def test_core_placements_keep_border_tiles_on_wider_layout(self):
        from sheet_music_recognition import _mixed_tile_decoder as mixed

        inner = ((16, 208), (16, 208))
        border = ((0, 208), (0, 208))
        spans = [inner, border]
        self.assertEqual(mixed._placements(spans, 192), (0, [0, None], 1))
        self.assertEqual(mixed._placements(spans, 224), (0, [0, 0], 2))

    def test_shaded_page_keeps_certified_core_tiles_when_wide_layout_fails(self):
        from sheet_music_recognition import _mixed_tile_decoder as mixed, _tile_decoder as decoder

        height, width = 1664, 2048
        gray = np.random.default_rng(31).integers(252, 255, size=(height, width), dtype=np.uint8)
        rows = [slice(y, y + 320) for y in reader.tile_starts(height)]
        columns = [slice(x, x + 320) for x in reader.tile_starts(width)]
        edge = reader._tile_edges()
        self.assertIsNone(decoder.select(gray, rows, columns, edge))
        regions, _, small, full, _, wide_column, window = mixed._plan_page(gray, edge, rows, columns)
        self.assertEqual(window, decoder.CORE_WINDOW)
        self.assertIsNone(wide_column)
        self.assertGreaterEqual(len(small), 32)
        self.assertTrue(full)
        self.assertTrue(all(job.top == 0 for job in full))
        owners = [index for job in small + full for index, _ in job.members]
        self.assertEqual(sorted(owners), list(range(len(regions))))

    def test_deferred_model_falls_back_when_compact_selection_is_declined(self):
        from sheet_music_recognition import _mixed_tile_decoder as mixed, _tile_decoder as decoder

        gray = np.full((1664, 2048), 254, dtype=np.uint8)
        loaded = []
        runtime = SimpleNamespace(
            set_tensor=lambda *_: None,
            invoke=lambda: None,
            get_tensor=lambda _: np.zeros((1, 320, 320), dtype=np.int64),
        )

        def initialize(engine, path, *_args, **_options):
            loaded.append(path)
            engine.runtime = runtime
            engine.input = {"index": 0}
            engine.output = {"index": 1}

        with patch.object(reader.sys, "platform", "win32"), patch.object(
            reader.Path, "is_file", return_value=True
        ), patch.object(mixed, "predict", return_value=None) as compact, patch.object(
            decoder, "select", return_value=None
        ), patch.object(reader.Interpreter, "_initialize_model", initialize):
            engine = reader.Interpreter(threads=2, _defer_model=True)
            np.testing.assert_array_equal(engine.predict(gray), np.zeros_like(gray))
            compact.assert_called_once()
            self.assertIs(engine.runtime, runtime)
            self.assertIsNone(engine._pending_model)

            # A later page can keep a resident stock engine from the prior mixed plan.
            path = engine.jar
            engine._tile_layout = (path, "mixed", None)
            engine._mixed_tile_recipe = (path, 320, None)
            np.testing.assert_array_equal(engine.predict(gray), np.zeros_like(gray))
            self.assertIs(engine.runtime, runtime)
            self.assertIsNone(engine._tile_layout)
            self.assertIsNone(engine._mixed_tile_recipe)
            self.assertEqual(len(loaded), 1)

    @unittest.skipUnless(sys.platform == "win32", "Tile decoder requires qualified Windows LiteRT")
    def test_core_copies_the_correct_offset_with_stock_litert(self):
        if inference_interpreter(bundled=True).__module__ != "ai_edge_litert.interpreter":
            self.skipTest("LiteRT not available")
        from ai_edge_litert.interpreter import Interpreter as Stock
        from sheet_music_recognition import _mixed_tile_decoder as mixed, _tile_decoder as decoder

        gray = np.full((320, 320), 255, dtype=np.uint8)
        gray[135:149:2, 13:303] = 0
        gray[169, 50:295:7] = 32
        top, left = 64, 48
        extent, inset = decoder.CORE_WINDOW, decoder.CORE_INSET
        bounds = (slice(top + inset, top + inset + extent), slice(left + inset, left + inset + extent))
        edge = reader._tile_edges()
        full = slice(0, 320)
        profile = decoder._axis_profile(320, [full], edge.max(axis=0))
        with patch.object(reader, "inference_interpreter", return_value=Stock):
            base = reader.Interpreter(threads=2)
            expected = np.zeros_like(gray)
            expected[bounds] = base.predict(gray)[bounds]
            del base
            core = reader.Interpreter(threads=2, _defer_model=True)
            path, _ = core._pending_model
            selection = decoder.Selection("joint", {}, left, [], {}, set(), extent)
            self.assertTrue(core._initialize_tile_decoder(path, selection))
        job = mixed._Job([(0, bounds)], top=top)
        actual = np.zeros_like(gray)
        mixed._run_jobs(core.runtime, gray, actual, [(full, full)], (profile, profile, edge), [job], left, extent)
        self.assertGreater(np.count_nonzero(expected), 0)
        np.testing.assert_array_equal(actual, expected)

    def test_mixed_shapes_release_first_arena_when_second_allocation_fails(self):
        from sheet_music_recognition import _mixed_tile_decoder as mixed

        gray = np.zeros((320, 320), dtype=np.uint8)
        plan = ([], None, [object()], [object()], 0, 0, 224)
        for memory_error in (False, True):
            with self.subTest(memory_error=memory_error):
                self.check_mixed_allocation_failure(mixed, gray, plan, memory_error)

    def check_mixed_allocation_failure(self, mixed, gray, plan, memory_error):
        engine = SimpleNamespace(edge=None, _tile_layout=None, _mixed_tile_recipe=None)
        windows = []
        released = []

        def initialize(path, selection):
            self.assertFalse(hasattr(engine, "runtime"))
            windows.append(selection.window)
            if len(windows) == 1:
                engine.runtime = SimpleNamespace(_release_page_arena=lambda: released.append(224))
                return True
            self.assertEqual(released, [224])
            if memory_error:
                raise MemoryError
            return False

        engine._initialize_tile_decoder = initialize
        with patch.object(mixed, "_plan_page", return_value=plan), patch.object(mixed, "_run_jobs") as run:
            self.assertIsNone(mixed.predict(engine, gray, [], [], "model", None))
        run.assert_called_once()
        self.assertEqual(windows, [224, 272])
        self.assertEqual(engine._pending_model, ("model", True))
        self.assertIsNone(engine._tile_layout)
        self.assertIsNone(engine._mixed_tile_recipe)
        self.assertFalse(hasattr(engine, "runtime"))

    def test_axis_profiles_preserve_final_tile_ownership_on_irregular_tails(self):
        from sheet_music_recognition import _tile_decoder as decoder

        height, width = 581, 788
        gray = np.full((height, width), 255, dtype=np.uint8)
        rows = [slice(y, y + 320) for y in reader.tile_starts(height)]
        columns = [slice(x, x + 320) for x in reader.tile_starts(width)]
        edge = reader._tile_edges()
        axis = edge.max(axis=0)
        profiles = (decoder._axis_profile(height, rows, axis),
                    decoder._axis_profile(width, columns, axis), edge)
        windows = list(reader._tile_windows(height, width, edge, rows, columns))
        owner = np.empty(gray.shape, dtype=np.uint8)
        for index, (region, wins) in enumerate(windows):
            np.copyto(owner[region], index, where=wins)
        box = (slice(0, 320), slice(0, 320))
        for index, (region, _) in enumerate(windows):
            ys, xs = np.nonzero(owner[region] == index)
            expected = ((int(ys.min()), int(ys.max()) + 1),
                        (int(xs.min()), int(xs.max()) + 1)) if ys.size else (None, None)
            self.assertEqual(expected, decoder._owned_spans(gray, profiles, region, box))

    def test_large_score_can_use_joint_layout_beyond_255_tiles(self):
        from sheet_music_recognition import _tile_decoder as decoder

        height, width, margin = 3728, 2880, 144
        gray = np.full((height, width), 255, dtype=np.uint8)
        for top in range(margin, height - margin - 44, 102):
            gray[top:top + 34:7, margin:width - margin] = 0
            for x in range(margin + 32, width - margin - 4, 29):
                y = top + (x // 29) % 11
                gray[y:y + 6, x:x + 4] = 32
        rows = [slice(y, y + 320) for y in reader.tile_starts(height)]
        columns = [slice(x, x + 320) for x in reader.tile_starts(width)]
        self.assertGreater(len(rows) * len(columns), 255)
        selection = decoder.select(gray, rows, columns, reader._tile_edges())
        self.assertIsNotNone(selection)
        self.assertEqual("joint", selection.mode)
        self.assertEqual(len(rows) * len(columns), len(selection.decisions))

    @unittest.skipUnless(sys.platform == "win32", "Tile decoder requires qualified Windows LiteRT")
    def test_sparse_pair_reuse_blank_and_incompatible_page(self):
        if inference_interpreter(bundled=True).__module__ != "ai_edge_litert.interpreter":
            self.skipTest("LiteRT not available")

        pair = np.full((320, 704), 255, dtype=np.uint8)
        pair[29:32, 20:24] = 35
        pair[48:51, 680:684] = 0
        base = reader.Interpreter(threads=2)
        optimized = reader.Interpreter(threads=2, _defer_model=True)
        expected = base.predict(pair)
        np.testing.assert_array_equal(optimized.predict(pair), expected)
        self.assertEqual("rows", optimized._tile_layout[1])
        arena = optimized.runtime

        pair[29, 21] = 42
        np.testing.assert_array_equal(optimized.predict(pair), base.predict(pair))
        white = np.full((576, 704), 255, dtype=np.uint8)
        np.testing.assert_array_equal(optimized.predict(white), np.zeros_like(white))
        self.assertIs(arena, optimized.runtime)
        del arena

        incompatible = np.full((320, 320), 255, dtype=np.uint8)
        incompatible[-1, -1] = 254
        with patch.object(optimized, "_initialize_model", side_effect=OSError("retry")):
            with self.assertRaisesRegex(OSError, "retry"):
                optimized.predict(incompatible)
        self.assertIsNotNone(optimized._pending_model)
        self.assertFalse(hasattr(optimized, "runtime"))
        np.testing.assert_array_equal(optimized.predict(incompatible), base.predict(incompatible))
        self.assertIsNone(optimized._tile_layout)
        self.assertEqual([1, 320, 320], optimized.output["shape"].tolist())

    @unittest.skipUnless(sys.platform == "win32", "Tile decoder requires qualified Windows LiteRT")
    def test_invalid_layout_uses_the_ordinary_model(self):
        if inference_interpreter(bundled=True).__module__ != "ai_edge_litert.interpreter":
            self.skipTest("LiteRT not available")
        gray = np.full((320, 704), 255, dtype=np.uint8)
        gray[20:23, 23:29] = 20
        base = reader.Interpreter(threads=2)
        deferred = reader.Interpreter(threads=2, _defer_model=True)
        with patch.object(reader._tile_decoder, "specialize", side_effect=ValueError) as specialize:
            np.testing.assert_array_equal(deferred.predict(gray), base.predict(gray))
        specialize.assert_called_once()
        self.assertIsNone(deferred._tile_layout)
