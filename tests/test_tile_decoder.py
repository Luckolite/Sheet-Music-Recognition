# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
from unittest.mock import patch

import numpy as np

from sheet_music_recognition import reader
from sheet_music_recognition.runtime import inference_interpreter


class TileDecoderTest(unittest.TestCase):
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
