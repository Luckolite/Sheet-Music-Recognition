// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** Eight-connected dark components in raster seed order, with exclusive right and bottom bounds. */
final class PrintedInkComponents {
    private int[] queue = new int[128];
    private long[] words = new long[0];

    interface Visitor {
        boolean visit(int left, int top, int right, int bottom, int count);
    }

    /** Stops scanning when the visitor returns false. */
    boolean scan(byte[] gray, int width, int left, int top, int right, int bottom,
            int threshold, Visitor visitor) {
        int columns = right - left, height = bottom - top;
        if (columns <= 0 || height <= 0) return true;
        int stride = (columns - 1) / 64 + 1;
        int size = stride * height;
        if (words.length < size) words = new long[size];
        int cutoff = Math.min(threshold, 255) + 1;
        for (int row = 0; row < height; row++)
            PackedGrayInk.fill(gray, (top + row) * width + left, columns, cutoff, words, row * stride);

        return visitRuns(left, top, columns, height, stride, visitor);
    }

    void scanSymbols(byte[] labels, int width, int height, Visitor visitor) {
        int stride = (width - 1) / 64 + 1;
        int size = stride * height;
        if (words.length < size) words = new long[size];
        for (int row = 0; row < height; row++)
            PackedGrayInk.fillSymbols(labels, row * width, width, words, row * stride);
        visitRuns(0, 0, width, height, stride, visitor);
    }

    private boolean visitRuns(int left, int top, int columns, int height, int stride, Visitor visitor) {
        int size = stride * height;
        for (int word = 0; word < size;) {
            if (words[word] == 0) {
                word++;
                continue;
            }
            int seedY = word / stride;
            int seedX = ((word - seedY * stride) << 6) + Long.numberOfTrailingZeros(words[word]);
            int queued = enqueue(seedX, seedY, stride, columns, 0), minX = columns, maxX = 0;
            int minY = seedY, maxY = minY, count = 0;
            for (int i = 0; i < queued; i += 2) {
                int first = queue[i], last = queue[i + 1], y = first / columns;
                int x = first - y * columns;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + (last - first));
                maxY = Math.max(maxY, y);
                count += last - first + 1;
                for (int near = y - 1; near <= y + 1; near += 2) {
                    if (near < 0 || near >= height) continue;
                    int lo = Math.max(0, x - 1);
                    int hi = Math.min(columns - 1, x + (last - first) + 1);
                    int base = near * stride;
                    for (int block = lo >>> 6; block <= (hi >>> 6); block++) {
                        long mask = block == (lo >>> 6) ? -1L << lo : -1L;
                        if (block == (hi >>> 6)) mask &= -1L >>> (63 - (hi & 63));
                        long bits;
                        while ((bits = words[base + block] & mask) != 0) {
                            int nextX = (block << 6) + Long.numberOfTrailingZeros(bits);
                            queued = enqueue(nextX, near, stride, columns, queued);
                        }
                    }
                }
            }
            if (!visitor.visit(left + minX, top + minY, left + maxX + 1, top + maxY + 1, count)) return false;
        }
        return true;
    }

    private int enqueue(int x, int y, int stride, int columns, int count) {
        int base = y * stride, block = x >>> 6;
        int first = block, last = block;
        // The nearest clear bit on either side bounds this run, even across whole ink words.
        long clear = ~words[base + first] & (-1L >>> (63 - (x & 63)));
        while (clear == 0 && first > 0) clear = ~words[base + --first];
        int left = (first << 6) + 64 - Long.numberOfLeadingZeros(clear);
        clear = ~words[base + last] & (-1L << x);
        while (clear == 0 && last + 1 < stride) clear = ~words[base + ++last];
        int right = (last << 6) + (Long.numberOfTrailingZeros(clear) - 1);
        first = left >>> 6;
        last = right >>> 6;
        long leftMask = -1L << left, rightMask = -1L >>> (63 - (right & 63));
        if (first == last) words[base + first] &= ~(leftMask & rightMask);
        else {
            words[base + first] &= ~leftMask;
            Arrays.fill(words, base + first + 1, base + last, 0L);
            words[base + last] &= ~rightMask;
        }
        if (count + 2 > queue.length) queue = Arrays.copyOf(queue, count * 2);
        queue[count] = y * columns + left;
        queue[count + 1] = y * columns + right;
        return count + 2;
    }
}
