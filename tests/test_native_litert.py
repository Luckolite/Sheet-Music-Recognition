# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Exercise the packaged native backend and its ordinary LiteRT fallback."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

import numpy as np

from sheet_music_recognition import runtime


@unittest.skipUnless(
    sys.platform == "win32" and Path(runtime.__file__).with_name("_score_sparse_avx2.dll").is_file(),
    "Windows native wheel required",
)
class NativeLiteRtTest(unittest.TestCase):
    def setUp(self):
        from ai_edge_litert.interpreter import Interpreter as Stock
        from sheet_music_recognition import _tile_decoder

        try:
            from sheet_music_recognition import _sparse_litert as native
        except ImportError as error:
            self.assertIs(runtime.inference_interpreter(bundled=True), Stock)
            self.skipTest(str(error))

        self.native = native
        self.stock = Stock
        self.factory = runtime.inference_interpreter(bundled=True)
        source = native._MODEL.read_bytes()
        layout = _tile_decoder.Selection("joint", {0: 0}, 24, [], {}, set())
        self.model = _tile_decoder.specialize(source, layout)
        self.tile = np.full((320, 320), 255, dtype=np.float32)
        self.tile[60:72:3, 4:314] = 0
        self.tile[64:76, 30:280:23] = 40

    def prepare(self, interpreter, image, top):
        main, *row_inputs = interpreter.get_input_details()
        tensor = interpreter.tensor(main["index"])()
        tensor[0, 0] = image
        del tensor
        for item in row_inputs:
            size = int(item["shape"][0])
            first = top // (272 // size)
            interpreter.set_tensor(item["index"], np.arange(first, first + size, dtype=np.int32))

    def pair(self):
        a = self.stock(model_content=self.model, num_threads=2)
        b = self.factory(model_content=self.model, num_threads=2)
        self.assertIsInstance(b, self.native.SparseInterpreter)
        return a, b

    def compare(self, a, b):
        index = a.get_output_details()[0]["index"]
        np.testing.assert_array_equal(a.get_tensor(index), b.get_tensor(index))

    def test_dynamic_crop_matches_stock(self):
        # Native callbacks bind their kernels when the interpreter is created.
        with (patch.object(self.native, "_wide_conv", wraps=self.native._wide_conv) as wide,
              patch.object(self.native, "_reuse_conv", wraps=self.native._reuse_conv) as reuse,
              patch.object(self.native, "_anchor_conv", wraps=self.native._anchor_conv) as anchors):
            a, b = self.pair()
            for model in a, b:
                model.allocate_tensors()
                self.prepare(model, self.tile, 24)
            a.invoke()
            b.invoke()
        self.assertIsNotNone(b._native)
        self.assertEqual(wide.call_count, 3)
        channel_counts = [call.args[5:7] for call in reuse.call_args_list]
        self.assertEqual(channel_counts, [(32, 32), (32, 64), (64, 64)])
        self.assertEqual([call.args[5] for call in anchors.call_args_list], [64, 128])
        self.compare(a, b)

    def test_allocation_failure(self):
        a, b = self.pair()
        a.allocate_tensors()
        with patch.object(self.native, "allocate", return_value=1):
            b.allocate_tensors()
        self.assertIsNone(b._native)
        for model in a, b:
            self.prepare(model, self.tile, 0)
            model.invoke()
        self.compare(a, b)

    def test_failed_invocation_replays_inputs_and_can_continue(self):
        a, b = self.pair()
        for model in a, b:
            model.allocate_tensors()
            self.prepare(model, self.tile, 24)

        invoke = self.native.invoke

        def fail_after_inference(handle):
            self.assertEqual(invoke(handle), 0)
            return 1

        with patch.object(self.native, "invoke", side_effect=fail_after_inference):
            b.invoke()
        self.assertIsNone(b._native)
        if self.native._worker_create is not None:
            self.assertIsNone(self.native._worker_exchange(None))
        a.invoke()
        self.compare(a, b)

        for model in a, b:
            self.prepare(model, np.flipud(self.tile), 8)
            model.invoke()
        self.compare(a, b)

    def test_core_matches_stock_with_specialized_kernels(self):
        from sheet_music_recognition import _tile_decoder

        selection = _tile_decoder.Selection("joint", {}, 48, [], {}, set(), _tile_decoder.CORE_WINDOW)
        graph = _tile_decoder.specialize(self.native._MODEL.read_bytes(), selection)
        with (patch.object(self.native, "_conv_valid32", wraps=self.native._conv_valid32) as valid,
              patch.object(self.native, "_conv_valid_final", wraps=self.native._conv_valid_final) as final,
              patch.object(self.native, "_conv_valid_group", wraps=self.native._conv_valid_group) as group,
              patch.object(self.native, "_conv_dilated_group", wraps=self.native._conv_dilated_group) as dilated,
              patch.object(self.native, "_conv_valid_group_tail", wraps=self.native._conv_valid_group_tail) as tail):
            a = self.stock(model_content=graph, num_threads=2)
            b = self.factory(model_content=graph, num_threads=2)
            self.assertIsInstance(b, self.native.SparseInterpreter)
            for model in a, b:
                model.allocate_tensors()
                self.prepare(model, self.tile, 0)
                model.invoke()
        self.assertIsNotNone(b._native)
        self.assertEqual(valid.call_count, 1)
        if self.native._worker_create is not None:
            self.assertIsNotNone(b._native.worker)
            self.assertIsNone(self.native._worker_exchange(None))
        self.assertEqual(final.call_count, 1)
        self.assertEqual([call.args[3:5] for call in group.call_args_list], [(54, 54), (102, 102)])
        self.assertEqual([call.args[7] for call in dilated.call_args_list], [2, 4])
        self.assertEqual([call.args[3:5] for call in tail.call_args_list], [(52, 52), (100, 100)])
        self.compare(a, b)

        with patch.object(self.native, "_conv_valid32", None):
            fallback = self.factory(model_content=graph, num_threads=2)
        self.assertIsInstance(fallback, self.stock)

    def test_missing_valid_kernel_uses_stock_core_graph(self):
        from sheet_music_recognition import _tile_decoder

        selection = _tile_decoder.Selection("joint", {}, 48, [], {}, set(), _tile_decoder.CORE_WINDOW)
        graph = _tile_decoder.specialize(self.native._MODEL.read_bytes(), selection)
        with patch.multiple(self.native, _conv_valid=None, _wide_conv_valid=None, _conv_valid_group_tail=None):
            runtime = self.factory(model_content=graph, num_threads=2)
        self.assertIsInstance(runtime, self.stock)
        runtime.allocate_tensors()
        np.testing.assert_array_equal(runtime.get_output_details()[0]["shape"], [1, 192, 192])

    def test_missing_reuse_kernel_uses_stock_graph(self):
        with patch.object(self.native, "_reuse_conv", None):
            model = self.factory(model_content=self.model, num_threads=2)
        self.assertIsInstance(model, self.stock)
        model.allocate_tensors()

    def test_unsupported_inputs_keep_stock_factory(self):
        stock = self.factory(model_content=self.model, num_threads=1)
        self.assertIsInstance(stock, self.stock)
        with patch.object(self.native, "_specialize", side_effect=ValueError("invalid layout")):
            stock = self.factory(model_content=self.model, num_threads=2)
        self.assertIsInstance(stock, self.stock)


if __name__ == "__main__":
    unittest.main()
