# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Smaller vertical inference arena for the reviewed bundled v4 graph."""
import struct

MODEL_SHA256 = "92ab7c34c27cb704d95f0cde247b3611d1563fdf1c114b9f9817abb18a7b6a93"
MAX_ARENA_HEIGHT = 304

# The pinned FlatBuffer stores vertical dimensions in resize and slice constants,
# the reshape target, and the spatial tensor shapes/signatures. Divisors
# follow the model's three 2x pooling levels. Width and weights are untouched.
_HEIGHT_FIELDS = {
    1: (1124, 3894500, 3894548, 3898104, 3898228, 3898384, 3898556, 3898636,
        3898744, 3901224, 3901396, 3901492, 3901616, 3901716, 3907272),
    2: (3894568, 3898900, 3899072, 3899152, 3899260, 3900792, 3900964, 3901052),
    4: (3894588, 3899412, 3899584, 3899664, 3899768, 3900356, 3900528, 3900620),
    8: (3899920, 3900092, 3900184),
}


def required_height(visible_height):
    # The last eight-row output group can read through its start + 85.
    # Keep that entire range, aligned to the original pooling grid.
    return min(320, ((visible_height - 1) // 8 + 11) * 8)


def specialize(content, height):
    """Patch only bytes whose source digest has already been verified."""
    output = bytearray(content)
    for divisor, offsets in _HEIGHT_FIELDS.items():
        for offset in offsets:
            struct.pack_into("<i", output, offset, height // divisor)
    return bytes(output)
