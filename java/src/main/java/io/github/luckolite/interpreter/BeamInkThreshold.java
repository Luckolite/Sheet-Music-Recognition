// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** Keeps shaded paper out of the narrow ink bands used to count beams. */
final class BeamInkThreshold {
    private BeamInkThreshold() {}

    private static final ThreadLocal<int[]> HISTOGRAM = ThreadLocal.withInitial(() -> new int[256]);

    static int at(byte[] gray, int width, int height, int x, int top, int bottom, float gap) {
        if (gray == null || gap < 3) return 165;
        int margin = Math.max(4, Math.round(gap));
        int left = Math.max(0, x - margin * 2), right = Math.min(width - 1, x + margin * 2);
        int first = Math.max(0, top - margin), last = Math.min(height - 1, bottom + margin);
        if (left > right || first > last) return 40;
        int rowWidth = right - left + 1;
        int rows = last - first + 1;
        int total = rowWidth * rows;
        int upperRank = total / 4 + 1;
        int firstPixel = first * width + left;
        // Sample three rows so a single shaded band does not steer the scan.
        // The histogram still evaluates the exact rank over the full window.
        if (isIntermediate(gray[firstPixel])
                && rows >= 3 && rowWidth >= 4
                && isIntermediate(gray[firstPixel + (rows / 2) * width + rowWidth / 2])
                && isIntermediate(gray[last * width + right])) {
            return shadedThreshold(gray, width, firstPixel, rowWidth, rows, upperRank, 0);
        }
        int brightNeeded = upperRank, darkNeeded = total - upperRank + 1;
        int unscanned = total;
        int start = firstPixel;
        // Keep the common black-and-white path allocation-free.
        while (brightNeeded <= unscanned && darkNeeded <= unscanned) {
            int bright = 0, dark = 0;
            for (int pixel = start, end = start + rowWidth; pixel < end; pixel++) {
                int tone = gray[pixel] & 255;
                bright += (196 - tone) >>> 31;
                dark += (tone - 73) >>> 31;
            }
            brightNeeded -= bright;
            darkNeeded -= dark;
            if (brightNeeded <= 0) return 165;
            if (darkNeeded <= 0) return 40;
            unscanned -= rowWidth;
            start += width;
        }
        // Once the dark cutoff is unreachable, only the bright proof can
        // finish early. Use the histogram when neither proof can finish.
        while (brightNeeded <= unscanned) {
            int bright = 0;
            for (int pixel = start, end = start + rowWidth; pixel < end; pixel++)
                bright += (196 - (gray[pixel] & 255)) >>> 31;
            brightNeeded -= bright;
            if (brightNeeded <= 0) return 165;
            unscanned -= rowWidth;
            start += width;
        }
        return shadedThreshold(gray, width, firstPixel, rowWidth, rows, upperRank,
                unscanned == 0 ? brightNeeded : 0);
    }

    private static boolean isIntermediate(byte pixel) {
        int tone = pixel & 255;
        return tone > 72 && tone < 197;
    }

    private static int shadedThreshold(
            byte[] gray, int width, int firstPixel, int rowWidth, int rows,
            int upperRank, int measuredRank) {
        // Index by raw tone so shaded windows need no per-pixel range checks.
        int[] histogram = HISTOGRAM.get();
        Arrays.fill(histogram, 0);
        if (rowWidth == width) {
            rowWidth *= rows;
            rows = 1;
        }
        for (int y = 0, start = firstPixel; y < rows; y++, start += width) {
            for (int pixel = start, end = start + rowWidth; pixel < end; pixel++)
                histogram[gray[pixel] & 255]++;
        }
        // Account for bright pixels as a group, then walk only the tones
        // that can change the clamped threshold.
        int remaining = measuredRank;
        if (remaining == 0) {
            remaining = upperRank;
            for (int tone = 255; tone >= 197; tone--) remaining -= histogram[tone];
            if (remaining <= 0) return 165;
        }
        for (int tone = 196; tone >= 73; tone--) {
            remaining -= histogram[tone];
            if (remaining <= 0) return tone - 32;
        }
        return 40;
    }
}
