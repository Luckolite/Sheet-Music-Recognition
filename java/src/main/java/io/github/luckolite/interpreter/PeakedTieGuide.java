// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Distinguishes an angular guide above two ledger notes from a returning tie. */
final class PeakedTieGuide {
    private PeakedTieGuide() {}

    static boolean isOnlyGuide(
            byte[] raster, int width, int height, float left, float right, float center, float gap) {
        if (raster == null || raster.length != (long) width * height
                || gap < 9 || gap > 30 || right - left < 5 * gap || right - left > 8 * gap)
            return false;
        int from = Math.round(left + 1.18f * gap);
        int to = Math.round(right - 1.18f * gap);
        int top = Math.round(center - 2.6f * gap), bottom = Math.round(center + 2.6f * gap);
        if (top < 0 || bottom >= height
                || left < 2.55f * gap || right + 2.55f * gap >= width) return false;
        int[] ridge = new int[to - from + 1];
        int apex = 0;
        for (int x = from; x <= to; x++) {
            int first = -1, last = -1, count = 0;
            for (int y = top; y <= bottom; y++) {
                if ((raster[y * width + x] & 255) >= 145) continue;
                if (first < 0) first = y;
                last = y;
                count++;
            }
            // A separate stroke in the interhead corridor must still be considered for a tie.
            if (count == 0 || last - first + 1 > count + 1 || count > .32f * gap
                    || center - last < 1.05f * gap || center - first > 2.35f * gap) return false;
            int index = x - from;
            ridge[index] = first + last;
            if (ridge[index] < ridge[apex]) apex = index;
        }
        int end = ridge.length - 1;
        if (apex < .32f * end || apex > .68f * end) return false;
        float rising = (ridge[0] - ridge[apex]) / (2f * apex);
        float falling = (ridge[end] - ridge[apex]) / (2f * (end - apex));
        if (rising < .10f || rising > .27f || falling < .10f || falling > .27f) return false;
        for (int i = 0; i <= end; i++) {
            float line = ridge[apex] * .5f + (i < apex ? rising * (apex - i) : falling * (i - apex));
            if (Math.abs(ridge[i] * .5f - line) > .07f * gap) return false;
        }
        int wing = Math.round(2.55f * gap);
        return wingContinues(raster, width, height, from, Math.round(left) - wing,
                        ridge[0] * .5f, -rising, gap)
                && wingContinues(raster, width, height, to, Math.round(right) + wing,
                        ridge[end] * .5f, falling, gap);
    }

    private static boolean wingContinues(
            byte[] raster, int width, int height, int start, int end, float center, float slope, float gap) {
        int step = Integer.compare(end, start), reach = Math.round(.16f * gap);
        for (int x = start + step; x != end + step; x += step) {
            int y = Math.round(center + slope * (x - start));
            if (y - reach < 0 || y + reach >= height) return false;
            boolean ink = false;
            for (int row = y - reach; row <= y + reach; row++) {
                if ((raster[row * width + x] & 255) < 145) {
                    ink = true;
                    break;
                }
            }
            if (!ink) return false;
        }
        return true;
    }
}
