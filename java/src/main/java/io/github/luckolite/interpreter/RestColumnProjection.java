// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** Exact dark-column counts for successive overlapping rest bands on one raster. */
final class RestColumnProjection {
    private final byte[] gray;
    private final int width;
    private final int[] rowDark, columns;
    private final int[][] rowRuns;
    private int[] scratch = new int[16];
    private int priorTop, priorBottom = -1;
    private boolean[] priorMask;
    private long priorInk;

    RestColumnProjection(byte[] gray, int width, int height, int[] rowDark) {
        this.gray = gray;
        this.width = width;
        this.rowDark = rowDark;
        columns = new int[width];
        rowRuns = new int[height][];
    }

    /** Returns null if the band has fewer than two ink pixels. Counts are valid until the next call. */
    int[] restBandCounts(int top, int bottom, boolean[] mask) {
        if (priorMask == mask && priorTop == top && priorBottom == bottom) return columns;
        int sharedTop = Math.max(top, priorTop), sharedBottom = Math.min(bottom, priorBottom);
        long kept = 0, added = 0;
        for (int row = top; row <= bottom; row++) {
            if (mask[row - top]) continue;
            if (row >= sharedTop && row <= sharedBottom && !priorMask[row - priorTop])
                kept += rowDark[row];
            else added += rowDark[row];
        }
        if (kept + added < 2) return null;
        long removed = priorInk - kept;
        if (removed > kept + width) {
            Arrays.fill(columns, 0);
            for (int row = top; row <= bottom; row++)
                if (!mask[row - top]) updateRow(row, 1);
        } else {
            if (removed > 0)
                for (int row = priorTop; row <= priorBottom; row++)
                    if (!priorMask[row - priorTop]
                            && (row < sharedTop || row > sharedBottom || mask[row - top]))
                        updateRow(row, -1);
            if (added > 0)
                for (int row = top; row <= bottom; row++)
                    if (!mask[row - top]
                            && (row < sharedTop || row > sharedBottom || priorMask[row - priorTop]))
                        updateRow(row, 1);
        }
        priorTop = top;
        priorBottom = bottom;
        priorMask = mask;
        priorInk = kept + added;
        return columns;
    }

    void readRow(int y, int[] rowLongest) {
        int count = 0, maximum = 0, used = 0, row = y * width;
        for (int x = 0; x < width; ) {
            while (x < width && (gray[row + x] & 255) >= 170) x++;
            if (x == width) break;
            int start = x++;
            while (x < width && (gray[row + x] & 255) < 170) x++;
            if (scratch.length - used < 2) scratch = Arrays.copyOf(scratch, scratch.length * 2);
            // A singleton needs one slot. Longer runs use a complemented start and an exclusive end.
            if (x == start + 1) scratch[used++] = start;
            else {
                scratch[used++] = ~start;
                scratch[used++] = x;
            }
            count += x - start;
            maximum = Math.max(maximum, x - start);
        }
        rowDark[y] = count;
        rowLongest[y] = maximum;
        if (used > 0) rowRuns[y] = Arrays.copyOf(scratch, used);
    }

    private void updateRow(int y, int delta) {
        int[] runs = rowRuns[y];
        if (runs == null) return;
        for (int i = 0; i < runs.length; ) {
            int start = runs[i++];
            if (start >= 0) columns[start] += delta;
            else {
                int end = runs[i++];
                for (int x = ~start; x < end; x++) columns[x] += delta;
            }
        }
    }
}
