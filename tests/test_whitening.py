# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import unittest
import numpy as np
from sheet_music_recognition.whitening import PaperBackgroundWhitening, raster_sha256


class WhiteningTest(unittest.TestCase):
    def test_clean_bypasses_missing_model(self):
        gray = np.full((96, 128), 255, np.uint8)
        gray[20:80:12, 10:118] = 0
        before = gray.copy()
        result = PaperBackgroundWhitening('missing-model.onnx').correct(gray)
        self.assertIs(result.decoder_gray, gray)
        self.assertFalse(result.changed)
        self.assertEqual(result.raw_sha256, result.decoder_sha256)
        np.testing.assert_array_equal(gray, before)

    def test_known_paper_keeps_source(self):
        owner = PaperBackgroundWhitening()
        owner._inference = lambda features: (np.full(features.shape[1:], .8, np.float32),
                                             np.full(features.shape[1:], .5, np.float32))
        gray = np.full((64, 96), 204, np.uint8)
        gray.flat[3000] = 102
        before = gray.copy()
        result = owner.correct(gray)
        self.assertTrue(result.changed)
        self.assertEqual(int(result.decoder_gray.flat[0]), 255)
        self.assertEqual(int(result.decoder_gray.flat[3000]), 0)
        np.testing.assert_array_equal(gray, before)

    def test_darkens_labeled_faded_strokes_on_white_paper(self):
        gray = np.full((96, 128), 255, np.uint8)
        labels = np.zeros_like(gray)
        gray[32, 12:115] = 178
        labels[32, 12:115] = 4
        gray[20:47, 80] = 150
        labels[20:47, 80] = 1
        gray[52:57, 35:43] = 165
        labels[52:57, 35:43] = 2
        gray[70, 30:90] = 0
        gray[71, 30:90] = 150
        labels[70:72, 30:90] = 4
        gray[12, 8] = 150
        gray[4, 90] = 235
        labels[4, 90] = 5
        before = gray.copy()
        before_labels = labels.copy()

        result = PaperBackgroundWhitening('missing-model.onnx').correct(gray, labels)

        self.assertTrue(result.changed)
        self.assertNotEqual(result.raw_sha256, result.decoder_sha256)
        self.assertEqual([140, 98, 120], result.decoder_gray[[32, 24, 54], [30, 80, 38]].tolist())
        self.assertEqual([255, 0, 150, 150, 235],
                         result.decoder_gray[[31, 70, 71, 12, 4], [30, 35, 35, 8, 90]].tolist())
        np.testing.assert_array_equal(gray, before)
        np.testing.assert_array_equal(labels, before_labels)

    def test_polishes_remaining_faded_ink_after_whitening(self):
        owner = PaperBackgroundWhitening()
        owner._inference = lambda features: (np.full(features.shape[1:], .8, np.float32),
                                             np.full(features.shape[1:], .8, np.float32))
        gray = np.full((64, 96), 204, np.uint8)
        labels = np.zeros_like(gray)
        gray[30, 12:80] = 168
        labels[30, 12:80] = 4
        gray[37, 12:80] = 168
        result = owner.correct(gray, labels)
        self.assertEqual([255, 178, 199], result.decoder_gray[[0, 30, 37], [0, 20, 20]].tolist())

    def test_invalid_input(self):
        for gray in (np.zeros((0, 1), np.uint8), np.zeros((2, 2), np.float32),
                     np.zeros((2, 2, 3), np.uint8)):
            with self.assertRaises(ValueError):
                PaperBackgroundWhitening().correct(gray)

    def test_model_hash_is_checked(self):
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'model.onnx'
            path.write_bytes(b'not the qualified model')
            with self.assertRaises(ValueError):
                PaperBackgroundWhitening(path).verify_model()

    def test_hash_has_dimensions(self):
        gray = np.zeros((8, 8), np.uint8)
        self.assertNotEqual(raster_sha256(gray), raster_sha256(gray.reshape(4, 16)))
