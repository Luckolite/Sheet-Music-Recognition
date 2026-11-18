package io.github.luckolite.interpreter;

import java.util.Arrays;

final class MeterCounterFlood {
    // The first run of an enclosed 8-connected component has an ink roof,
    // including its two upper diagonals. Return its index in the padded mask.
    static int nextRoof(byte[] gray, int width, int left, int first, int w, int h, int from) {
        int stride = w + 2;
        int firstRow = from / stride;
        int firstColumn = from % stride;
        for (int row = Math.max(2, firstRow); row < h; row++) {
            int base = (first + row - 1) * width + left - 1;
            int column = row == firstRow ? Math.max(2, firstColumn) : 2;
            while (column < w) {
                int at = base + column;
                if ((gray[at - 1] & 255) <= 160 && (gray[at] & 255) > 160
                        && (gray[at - width - 1] & 255) <= 160) {
                    int end = column;
                    while (end <= w && (gray[base + end] & 255) > 160) end++;
                    if (end > w) break;
                    int roof = at - width, last = base + end - width;
                    while (roof <= last && (gray[roof] & 255) <= 160) roof++;
                    if (roof > last) return row * stride + column;
                    column = end;
                }
                column++;
            }
        }
        return -1;
    }

    static byte[] paperMask(byte[] gray, int width, int left, int first, int w, int h) {
        int stride = w + 2;
        byte[] paper = new byte[stride * (h + 2)];
        for (int y = 0, at = first * width + left; y < h; y++, at += width) {
            int start = (y + 1) * stride + 1;
            for (int x = 0; x < w; x++)
                if ((gray[at + x] & 255) > 160) paper[start + x] = 1;
        }
        return paper;
    }

    final int[] runBounds;
    int boundCount, area;

    MeterCounterFlood(int width, int height) {
        // At most ceil(width / 2) disjoint bright runs per row.
        runBounds = new int[2 * ((width + 1) / 2) * height];
    }

    void drain(byte[] paper, int stride, int seed) {
        boundCount = area = 0;
        mark(paper, seed);
        for (int read = 0; read < boundCount; read += 2) {
            int left = runBounds[read], right = runBounds[read + 1];
            for (int offset = -stride; offset <= stride; offset += 2 * stride) {
                for (int at = left + offset - 1, last = right + offset + 1; at <= last; at++) {
                    if (paper[at] != 0) at = mark(paper, at);
                }
            }
        }
    }

    private int mark(byte[] paper, int seed) {
        int left = seed, right = seed;
        while (paper[left - 1] != 0) left--;
        while (paper[right + 1] != 0) right++;
        Arrays.fill(paper, left, right + 1, (byte) 0);
        runBounds[boundCount++] = left;
        runBounds[boundCount++] = right;
        area += right - left + 1;
        return right;
    }
}
