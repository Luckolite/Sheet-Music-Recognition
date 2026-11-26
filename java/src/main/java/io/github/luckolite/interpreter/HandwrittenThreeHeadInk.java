// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

final class HandwrittenThreeHeadInk {
    private HandwrittenThreeHeadInk() {}

    /** Distinguish the lower curl of a large handwritten 3 from a note near the top rule. */
    static boolean matches(byte[] gray, int width, int height, float x, float top, float gap) {
        if (gray == null || gray.length != (long) width * height || gap < 6 || gap > 38
                || x - 1.9f * gap < 0 || x + 1.5f * gap >= width
                || top - 4.7f * gap < 0 || top + 1.8f * gap >= height) return false;
        int upperBowl = 0, rightSpine = 0, lowerHook = 0, lowerBody = 0;
        for (int y = Math.round(top - 4.7f * gap); y < top - .14f * gap; y++) {
            float fromTop = (y - top) / gap;
            int left = ink(gray, width, y, x - 1.72f * gap, x - .68f * gap);
            int center = ink(gray, width, y, x - .38f * gap, x + .18f * gap);
            int right = ink(gray, width, y, x + .59f * gap, x + 1.48f * gap);
            if (fromTop < -2.72f && left >= .18f * gap && right >= .25f * gap && center == 0)
                upperBowl++;
            if (fromTop >= -1.62f && fromTop <= -.66f && left == 0
                    && right >= .25f * gap && center <= .08f * gap) rightSpine++;
            if (fromTop >= -.62f && left >= .20f * gap && right >= .25f * gap)
                lowerHook++;
        }
        if (upperBowl < 1.15f * gap || rightSpine < .7f * gap || lowerHook < .24f * gap)
            return false;
        for (int y = Math.round(top + .29f * gap); y <= top + .78f * gap; y++)
            if (ink(gray, width, y, x - .48f * gap, x + .42f * gap) >= .55f * gap) lowerBody++;
        if (lowerBody < .27f * gap) return false;
        for (int y = Math.round(top + 1.25f * gap); y <= top + 1.65f * gap; y++)
            if (ink(gray, width, y, x - .42f * gap, x + .42f * gap) >= .35f * gap) return false;
        return true;
    }

    private static int ink(byte[] gray, int width, int y, float left, float right) {
        int total = 0;
        for (int x = Math.round(left); x <= Math.round(right); x++)
            if ((gray[y * width + x] & 255) <= 150) total++;
        return total;
    }
}
