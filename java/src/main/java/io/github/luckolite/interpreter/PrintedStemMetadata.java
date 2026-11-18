// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Source-only shaft direction for serialization; never selects a rhythmic or performance voice. */
final class PrintedStemMetadata {
    private PrintedStemMetadata() {}

    static int detect(
            byte[] gray,
            int w,
            int h,
            int left,
            int right,
            int top,
            int bottom,
            float centerX,
            float centerY,
            float gap,
            float staffSlope) {
        if (gray == null
                || w <= 0
                || h <= 0
                || gray.length != (long) w * h
                || left < 0
                || right >= w
                || top < 0
                || bottom >= h
                || right < left
                || bottom < top
                || !Float.isFinite(centerX)
                || !Float.isFinite(centerY)
                || centerX < left
                || centerX > right
                || centerY < top
                || centerY > bottom
                || !Float.isFinite(gap)
                || gap < 4
                || gap > Math.min(w, h) * .25f
                || !Float.isFinite(staffSlope)
                || Math.abs(staffSlope) > .35f) return 0;
        boolean up = shaft(gray, w, h, right, centerY, gap, staffSlope, -1),
                down = shaft(gray, w, h, left, centerY, gap, staffSlope, 1);
        return up == down ? 0 : up ? 1 : -1;
    }

    private static boolean shaft(
            byte[] gray, int w, int h, int edge, float y, float gap, float slope, int direction) {
        int margin = Math.max(2, Math.round(gap * .22f)),
                flank = Math.max(3, Math.round(gap * .4f));
        int start = Math.max(2, Math.round(gap * .25f)), length = Math.round(gap * 2.25f);
        int radius = Math.max(4, Math.round(gap * .65f)), half = Math.max(2, Math.round(gap * .3f));
        for (int origin = edge - margin; origin <= edge + margin; origin++) {
            int hits = 0, narrow = 0, total = 0, blank = 0;
            boolean disconnected = false;
            for (int d = start; d <= length; d++) {
                int row = Math.round(y + direction * d),
                        x = Math.round(origin - slope * direction * d);
                if (row < 1 || row >= h - 1 || x - flank < 0 || x + flank >= w) {
                    disconnected = true;
                    break;
                }
                int core = Math.min(gray[row * w + x] & 255,
                        Math.min(gray[row * w + x - 1] & 255, gray[row * w + x + 1] & 255));
                int left = Math.max(0, x - radius), right = Math.min(w - 1, x + radius);
                int top = Math.max(0, row - half), bottom = Math.min(h - 1, row + half);
                int samples = (right - left + 1) * (bottom - top + 1);
                int needed = samples - (int) ((samples - 1L) * 85 / 100);
                total++;
                if (core <= 231 && paperAtLeast(gray, w, left, right, top, bottom, needed, core + 24)) {
                    hits++;
                    blank = 0;
                    int side = Math.min(gray[row * w + x - flank] & 255, gray[row * w + x + flank] & 255) - core;
                    if (side >= 16) {
                        // Smallest integer delta with round(delta * .3f) > side.
                        int tooBright = core + (10 * side + 7) / 3;
                        if (tooBright > 255 || !paperAtLeast(gray, w, left, right, top, bottom, needed, tooBright))
                            narrow++;
                    }
                } else if (++blank > Math.max(1, Math.round(gap * .12f))) {
                    disconnected = true;
                    break;
                }
            }
            if (!disconnected
                    && total >= gap * 1.9f
                    && hits >= total * .92f
                    && narrow >= total * .6f) return true;
        }
        return false;
    }

    /** The 85th percentile reaches the cutoff iff enough samples reach it. */
    private static boolean paperAtLeast(byte[] gray, int width, int left, int right,
            int top, int bottom, int needed, int cutoff) {
        for (int y = top; y <= bottom; y++) {
            int end = y * width + right;
            for (int p = y * width + left; p <= end; p++)
                if ((gray[p] & 255) >= cutoff && --needed == 0) return true;
        }
        return false;
    }
}