# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Original tensors exercise cache ownership and custom-model compatibility."""
import unittest
from unittest.mock import patch
import numpy as np
from sheet_music_recognition import reader


class FakeRuntime:
    def __init__(self, stateful=False):
        self.calls = 0
        self.stateful = stateful
        self.output = np.zeros((1, 320, 320), np.int64)
        yy, xx = np.mgrid[:320, :320]
        self.pattern = xx // 7 + yy // 11

    def set_tensor(self, index, values):
        self.input = values.copy()

    def invoke(self):
        self.calls += 1
        marker = self.calls if self.stateful else int(self.input.sum(dtype=np.float64)) + 1
        self.output[0] = (self.pattern + marker) % 6

    def get_tensor(self, index):
        # Deliberately return a reused buffer rather than a defensive copy.
        return self.output


def make_reader(known=True, stateful=False):
    instance = object.__new__(reader.Interpreter)
    instance.runtime = FakeRuntime(stateful)
    instance.input = {"index": 0}
    instance.output = {"index": 1}
    instance.model_sha256 = reader.MODEL_SHA256 if known else "custom-model"
    yy, xx = np.mgrid[:320, :320]
    instance.edge = np.minimum.reduce([xx, yy, 319 - xx, 319 - yy]) + 1
    return instance


def original_prediction(owner, gray):
    height, width = gray.shape
    labels = np.zeros_like(gray)
    confidence = np.zeros_like(gray)
    for top in reader.tile_starts(height):
        for left in reader.tile_starts(width):
            ch, cw = min(320, height - top), min(320, width - left)
            tile = np.full((320, 320), 255, np.float32)
            tile[:ch, :cw] = gray[top:top + ch, left:left + cw]
            owner.runtime.set_tensor(owner.input["index"], np.repeat(tile[None, None], 3, axis=1))
            owner.runtime.invoke()
            prediction = owner.runtime.get_tensor(owner.output["index"])[0]
            wins = owner.edge[:ch, :cw] >= confidence[top:top + ch, left:left + cw]
            labels[top:top + ch, left:left + cw][wins] = prediction[:ch, :cw][wins]
            confidence[top:top + ch, left:left + cw][wins] = owner.edge[:ch, :cw][wins]
    return labels


class ExactWhitePredictionCacheTest(unittest.TestCase):
    def test_arena_reused_after_invalid_prediction_and_padded_tiles(self):
        gray = np.full((127, 910), 255, np.uint8)
        gray[:, 400:405] = 0
        owner = make_reader()
        runtime = owner.runtime
        runtime.input = np.empty((1, 3, 320, 320), np.float32)
        runtime.tensor = lambda index: (lambda: (runtime.input if index == 0 else runtime.output).view())
        runtime.set_tensor = runtime.get_tensor = lambda *args: self.fail("Unexpected tensor copy")
        invoke = runtime.invoke
        runtime.invoke = lambda: runtime.output.fill(256)
        with self.assertRaisesRegex(ValueError, "outside 0..5"):
            owner.predict(gray)
        runtime.invoke = invoke
        np.testing.assert_array_equal(original_prediction(make_reader(), gray), owner.predict(gray))

    def assert_original(self, gray, known=True, stateful=False):
        before = gray.copy()
        baseline, candidate = make_reader(known, stateful), make_reader(known, stateful)
        expected = original_prediction(baseline, gray)
        actual = candidate.predict(gray)
        np.testing.assert_array_equal(expected, actual)
        np.testing.assert_array_equal(before, gray)
        self.assertGreater(np.count_nonzero(actual), 0, "White must retain actual nonzero model output")
        return baseline, candidate

    def test_partial_and_overlapping_exact_white_tensors_keep_actual_predictions(self):
        for width, height in [(1, 1), (321, 319), (17, 321), (705, 705)]:
            with self.subTest(width=width, height=height):
                gray = np.full((height, width), 255, np.uint8)
                gray.setflags(write=False)
                baseline, candidate = self.assert_original(gray)
                self.assertEqual(1, candidate.runtime.calls)
                self.assertEqual(len(reader.tile_starts(width)) * len(reader.tile_starts(height)), baseline.runtime.calls)

    def test_cached_output_owns_values_across_interleaved_nonwhite_tiles(self):
        gray = np.full((705, 705), 255, np.uint8)
        gray[0, 0] = 0
        gray[512, 512] = 18
        baseline, candidate = self.assert_original(gray)
        self.assertLess(candidate.runtime.calls, baseline.runtime.calls)
        expected = original_prediction(make_reader(), gray)
        first_calls = candidate.runtime.calls
        np.testing.assert_array_equal(expected, candidate.predict(gray))
        self.assertEqual(first_calls * 2, candidate.runtime.calls, "Prediction cache must be local to one page call")

    def test_default_edge_masks_preserve_ties_at_overlapping_tail_tiles(self):
        for height, width in ((127, 99), (319, 321), (321, 319), (513, 705), (896, 1091)):
            with self.subTest(height=height, width=width):
                gray = np.arange(height * width, dtype=np.uint8).reshape(height, width)[:, ::-1]
                original, optimized = make_reader(False, True), make_reader(False, True)
                optimized.edge = reader._tile_edges()
                np.testing.assert_array_equal(original_prediction(original, gray), optimized.predict(gray))
                self.assertEqual(original.runtime.calls, optimized.runtime.calls)

    def test_repeated_ink_requires_every_pixel_to_match(self):
        gray = np.full((320, 896), 255, np.uint8)
        gray[16::48] = 0
        # The sample grid misses this pixel, and only the second tile contains it.
        gray[3, 330] = 8
        baseline, cached = self.assert_original(gray)
        self.assertEqual((4, 2), (baseline.runtime.calls, cached.runtime.calls))
        baseline, custom = self.assert_original(gray, known=False, stateful=True)
        self.assertEqual((4, 4), (baseline.runtime.calls, custom.runtime.calls))

    def test_tinted_paper_with_thin_rules_between_sample_points(self):
        gray = np.full((320, 896), 254, np.uint8)
        gray[7::32] = 37
        baseline, candidate = self.assert_original(gray)
        self.assertEqual((4, 1), (baseline.runtime.calls, candidate.runtime.calls))

    def test_checksum_collision_compares_original_pixels(self):
        gray = np.full((127, 896), 255, np.uint8)
        gray[3, 390] = 254
        with patch.object(reader.zlib, "crc32", return_value=0):
            baseline, candidate = self.assert_original(gray[:, ::-1])
        self.assertGreater(candidate.runtime.calls, 1)
        self.assertLessEqual(candidate.runtime.calls, baseline.runtime.calls)

    def test_tile_cache_releases_finished_predictions_and_keeps_nearby_reuses(self):
        keys = [1, 2, 2, 3, 3, 1, 4, 4]
        retained = set()
        hits = []
        for index, (key, keep) in enumerate(reader._tile_cache_plan(keys, capacity=1)):
            if key in retained:
                hits.append(index)
            retained.discard(key)
            if keep:
                retained.add(key)
            self.assertLessEqual(len(retained), 1)
        self.assertEqual([2, 4, 7], hits)

    def test_sparse_pairs_preserve_extreme_context_and_tile_order(self):
        def engine(enable):
            result = make_reader()
            result.edge = reader._tile_edges()
            result._sparse_pairs_enabled = enable
            runtime = result.runtime
            groups = (np.arange(320) // 8) * 8
            low, high = np.maximum(0, groups - 78), np.minimum(319, groups + 85)

            def local_prediction():
                runtime.calls += 1
                pixels = runtime.input[0, 0]
                values = pixels.copy()
                for y in (low, high):
                    for x in (low, high):
                        values += pixels[np.ix_(y, x)]
                runtime.output[0] = (values + runtime.pattern) % 6

            runtime.invoke = local_prediction
            return result

        gray = np.full((320, 2403), 255, np.uint8)
        for i in range(10):
            y = 3 + i if i % 2 == 0 else 307 - i
            x = 192 * i + 151
            gray[y:y + 4, x:x + 7] = 20 + i
        original, paired = engine(False), engine(True)
        expected = original.predict(gray)
        self.assertTrue(np.array_equal(expected, paired.predict(gray)))
        self.assertLess(paired.runtime.calls, original.runtime.calls)
        first_page_calls = paired.runtime.calls
        gray[3, 151] += 1
        self.assertTrue(np.array_equal(original.predict(gray), paired.predict(gray)))
        self.assertEqual(first_page_calls - 1, paired.runtime.calls - first_page_calls)

    def test_bundled_litert_pair_skips_the_white_reference_inference(self):
        class PointwiseRuntime(FakeRuntime):
            def invoke(self):
                self.calls += 1
                self.output[0] = (255 - self.input[0, 0]) % 6

        class BundledLiteRt(PointwiseRuntime):
            __module__ = "ai_edge_litert.interpreter"

        gray = np.full((320, 2403), 255, np.uint8)
        for index in range(10):
            y = 3 + index if index % 2 == 0 else 307 - index
            x = 192 * index + 151
            gray[y:y + 4, x:x + 7] = 20 + index
        fallback, known = make_reader(), make_reader()
        for engine in (fallback, known):
            engine.edge = reader._tile_edges()
            engine._sparse_pairs_enabled = True
        fallback.runtime = PointwiseRuntime()
        known.runtime = BundledLiteRt()

        np.testing.assert_array_equal(fallback.predict(gray), known.predict(gray))
        self.assertEqual(fallback.runtime.calls - 1, known.runtime.calls)
        self.assertFalse(known._white_prediction.flags.writeable)

    def test_sparse_pair_limits_and_white_reference_cost(self):
        gray = np.full((320, 320 * 34), 160, np.uint8)
        regions = [(slice(0, 320), slice(i * 320, (i + 1) * 320)) for i in range(34)]
        for index, row in ((31, 0), (32, 319)):
            tile = gray[regions[index]]
            tile.fill(255)
            tile[row, 160] = 0
        decisions = [(None, False)] * len(regions)
        # These regions are in separate scheduling windows.
        self.assertEqual(({}, set()), reader._sparse_pairs(gray, regions, decisions))
        gray[regions[30]] = gray[regions[32]]
        # One pair alone cannot pay for an additional white reference inference.
        self.assertEqual(({}, set()), reader._sparse_pairs(gray, regions, decisions))
        gray[regions[0]] = 255
        pairs, white = reader._sparse_pairs(gray, regions, decisions)
        self.assertEqual([30], list(pairs))
        self.assertEqual(31, pairs[30][0])
        self.assertEqual({0}, white)

    def test_custom_stateful_model_keeps_one_invocation_per_tile(self):
        gray = np.full((705, 705), 255, np.uint8)
        baseline, candidate = self.assert_original(gray, known=False, stateful=True)
        self.assertEqual(16, baseline.runtime.calls)
        self.assertEqual(baseline.runtime.calls, candidate.runtime.calls)

    def test_white_output_is_validated_before_narrowing(self):
        gray = np.full((320, 320), 255, np.uint8)
        for invalid_class in (-256, 256):
            with self.subTest(invalid_class=invalid_class):
                owner = make_reader()
                output = np.zeros((1, 320, 320), np.int64)
                output[0, 0, 0] = invalid_class
                owner.runtime.get_tensor = lambda index: output
                with self.assertRaisesRegex(ValueError, "classes outside 0..5"):
                    owner.predict(gray)


if __name__ == "__main__":
    unittest.main()
