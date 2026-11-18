// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** Rectifies the printed staff using the original rounded source row at every column. */
final class StaffBandRows {
    private final int[] ends;
    private final float[] minima, maxima;
    private int count;

    private StaffBandRows(float[] bottoms, float dy) {
        // Short, fragmented spans are cheaper to sample directly.
        int capacity = bottoms.length / 8 + 1;
        ends = new int[capacity];
        minima = new float[capacity];
        maxima = new float[capacity];
        for (int x = 0; x < bottoms.length;) {
            if (count == capacity) {
                count = 0;
                return;
            }
            float min = bottoms[x], max = min;
            int source = Math.round(min + dy);
            for (x++; x < bottoms.length && Math.round(bottoms[x] + dy) == source; x++) {
                min = Math.min(min, bottoms[x]);
                max = Math.max(max, bottoms[x]);
            }
            ends[count] = x;
            minima[count] = min;
            maxima[count++] = max;
        }
    }

    static void rectify(byte[] gray, int width, int height, int first, float bottom,
                        float[] bottoms, byte[] flat) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (float value : bottoms) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        StaffBandRows spans = null;
        for (int offset = 0, y = first; offset < flat.length; offset += width, y++) {
            float dy = y - bottom;
            int row = Math.round(min + dy);
            if (!Float.isNaN(min) && row == Math.round(max + dy)) {
                copy(gray, width, height, row, flat, offset, 0, width);
                continue;
            }
            if (spans == null) spans = new StaffBandRows(bottoms, dy);
            int start = 0;
            for (int i = 0; i < spans.count; i++) {
                int end = spans.ends[i];
                // Rounding can change near half-pixel boundaries on later rows.
                float low = spans.minima[i];
                row = Math.round(low + dy);
                if (!Float.isNaN(low) && row == Math.round(spans.maxima[i] + dy))
                    copy(gray, width, height, row, flat, offset, start, end);
                else
                    sample(gray, width, height, bottoms, dy, flat, offset, start, end);
                start = end;
            }
            if (spans.count == 0) sample(gray, width, height, bottoms, dy, flat, offset, 0, width);
        }
    }

    private static void copy(byte[] gray, int width, int height, int row, byte[] flat,
                             int offset, int start, int end) {
        if (row >= 0 && row < height)
            System.arraycopy(gray, row * width + start, flat, offset + start, end - start);
        else
            Arrays.fill(flat, offset + start, offset + end, (byte) 255);
    }

    private static void sample(byte[] gray, int width, int height, float[] bottoms, float dy,
                               byte[] flat, int offset, int start, int end) {
        for (int x = start; x < end; x++) {
            int row = Math.round(bottoms[x] + dy);
            flat[offset + x] = row >= 0 && row < height ? gray[row * width + x] : (byte) 255;
        }
    }
}
