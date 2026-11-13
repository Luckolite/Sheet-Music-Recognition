# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Original arrays verify input buffer reuse and exact model tensor values."""
import unittest
from unittest.mock import patch

import numpy as np
from test_exact_white_prediction_cache import make_reader, original_prediction


class ExactWhitePredictionPackingTest(unittest.TestCase):
    def test_invocations_reuse_one_buffer_with_exact_rgb_values(self):
        for pattern in ('white', 'mixed', 'almost-white'):
            with self.subTest(pattern=pattern):
                gray = np.full((705, 705), 255, np.uint8)
                if pattern == 'mixed':
                    gray[0, 0] = 0
                    gray[512, 512] = 18
                elif pattern == 'almost-white':
                    gray.fill(254)
                baseline, candidate = make_reader(), make_reader()
                expected = original_prediction(baseline, gray)
                set_tensor = candidate.runtime.set_tensor
                with patch.object(candidate.runtime, 'set_tensor', wraps=set_tensor) as packed:
                    actual = candidate.predict(gray)
                    self.assertEqual(candidate.runtime.calls, packed.call_count)
                    buffers = [call.args[1] for call in packed.call_args_list]
                    self.assertTrue(all(tensor is buffers[0] for tensor in buffers))
                    self.assertEqual((1, 3, 320, 320), buffers[0].shape)
                    self.assertEqual(np.float32, buffers[0].dtype)
                    self.assertTrue(buffers[0].flags.c_contiguous)
                np.testing.assert_array_equal(expected, actual)
                if pattern == 'white':
                    self.assertEqual(1, candidate.runtime.calls)
                elif pattern == 'almost-white':
                    self.assertEqual(16, candidate.runtime.calls)

    def test_unknown_model_fingerprint_preserves_stateful_invocations(self):
        gray = np.full((705, 705), 255, np.uint8)
        baseline, candidate = make_reader(False, True), make_reader(False, True)
        del candidate.model_sha256
        expected = original_prediction(baseline, gray)
        np.testing.assert_array_equal(expected, candidate.predict(gray))
        self.assertEqual(16, candidate.runtime.calls)

    def test_partial_tiles_match_reference_tensor_by_tensor(self):
        gray = np.arange(705 * 17, dtype=np.uint8).reshape(705, 17)[::-1]
        baseline, candidate = make_reader(False), make_reader(False)

        def capture(owner):
            tensors = []
            set_tensor = owner.runtime.set_tensor

            def save(index, tensor):
                tensors.append(tensor.copy())
                set_tensor(index, tensor)

            owner.runtime.set_tensor = save
            return tensors

        expected, actual = capture(baseline), capture(candidate)
        np.testing.assert_array_equal(original_prediction(baseline, gray), candidate.predict(gray))
        self.assertEqual(len(expected), len(actual))
        for reference, tensor in zip(expected, actual):
            np.testing.assert_array_equal(reference, tensor)
