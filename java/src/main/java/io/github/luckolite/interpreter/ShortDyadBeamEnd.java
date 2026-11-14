// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Prove double-beam ink, then require both rail bands to end at the shared shaft. */
final class ShortDyadBeamEnd {
    private ShortDyadBeamEnd() {}

    static boolean proves(byte[] raw, int width, float gap, int[] members,
            int[] earlier, int[] previous, int[] end) {
        int dark = 0;
        for (int pixel : members) {
            int x = pixel % width, y = pixel / width;
            float depth = at(previous, end, x) - y;
            if (x < end[0] - gap * 1.08f || x > end[0] + gap * .23f
                    || depth < -gap * .24f || depth > gap * 1.32f) return false;
            if ((raw[pixel] & 255) < 155) dark++;
        }
        return dark >= members.length * .89f
                && LinkedFilledThirds.joined(raw, width, gap, earlier, previous)
                && LinkedFilledThirds.ends(raw, width, gap, previous, end);
    }

    private static float at(int[] left, int[] right, int x) {
        return left[1] + (right[1] - left[1]) * (x - left[0]) / (float) (right[0] - left[0]);
    }
}
