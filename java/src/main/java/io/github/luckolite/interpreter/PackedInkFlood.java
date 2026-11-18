// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Ordered pixels of one eight-connected or vertically extended grayscale component. */
final class PackedInkFlood {
    private final int width, height, verticalReach;
    private final long[] ink;
    final int[] queue;
    int left, right, top, bottom;
    private int wordIndex;

    PackedInkFlood(byte[] gray, int width, int height, int cutoff, int verticalReach, int first) {
        this.width = width;
        this.height = height;
        this.verticalReach = verticalReach;
        ink = first < gray.length ? PackedGrayInk.classify(gray, 0, gray.length, cutoff) : new long[0];
        wordIndex = first >>> 6;
        int count = 0;
        for (long word : ink) count += Long.bitCount(word);
        queue = new int[count];
    }

    int next() {
        while (wordIndex < ink.length && ink[wordIndex] == 0) wordIndex++;
        if (wordIndex >= ink.length) return 0;
        int origin = (wordIndex << 6) + Long.numberOfTrailingZeros(ink[wordIndex]);
        int n = 1;
        queue[0] = origin;
        ink[origin >>> 6] &= ~(1L << origin);
        left = width;
        right = 0;
        top = height;
        bottom = 0;
        for (int start = 0; start < n; start++) {
            int at = queue[start], y = at / width, x = at - y * width;
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
            int above = Math.min(y, verticalReach), below = Math.min(height - 1 - y, verticalReach);
            int first = at - above * width, last = at + below * width;
            int before = x > 0 ? 1 : 0, after = x + 1 < width ? 1 : 0;
            for (int center = first; center <= last; center += width) {
                int lo = center - before, hi = center + after;
                int word = lo >>> 6, lastWord = hi >>> 6;
                // A three-pixel horizontal neighborhood can straddle two packed words.
                long mask = -1L << lo;
                for (; word <= lastWord; word++) {
                    if (word == lastWord) mask &= -1L >>> (63 - (hi & 63));
                    long neighbors = ink[word] & mask;
                    ink[word] ^= neighbors;
                    while (neighbors != 0) {
                        queue[n++] = (word << 6) + Long.numberOfTrailingZeros(neighbors);
                        neighbors &= neighbors - 1;
                    }
                    mask = -1L;
                }
                if (center == last) break;
            }
        }
        return n;
    }
}
