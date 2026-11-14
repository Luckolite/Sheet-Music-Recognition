// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** Exact dark-column counts for successive overlapping rest bands on one raster. */
final class RestColumnProjection {
    private final byte[] gray;
    private final int width;
    private final int[] rowDark, columns;
    private final int[][] rowColumns;
    private int priorTop, priorBottom = -1;
    private boolean[] priorMask;
    private long priorInk;

    RestColumnProjection(byte[] gray, int width, int height, int[] rowDark) {
        this.gray = gray;
        this.width = width;
        this.rowDark = rowDark;
        columns = new int[width];
        rowColumns = new int[height][];
    }

    /** Consumers read these counts synchronously; the same storage serves the next band. */
    int[] sum(int top, int bottom, boolean[] mask) {
        if (priorMask == mask && priorTop == top && priorBottom == bottom) return columns;
        int sharedTop = Math.max(top, priorTop), sharedBottom = Math.min(bottom, priorBottom);
        long kept = 0, added = 0;
        for (int row = top; row <= bottom; row++) {
            if (mask[row - top]) continue;
            if (row >= sharedTop && row <= sharedBottom && !priorMask[row - priorTop])
                kept += rowDark[row];
            else added += rowDark[row];
        }
        long removed = priorInk - kept;
        if (removed > kept + width) {
            Arrays.fill(columns, 0);
            for (int row = top; row <= bottom; row++)
                if (!mask[row - top]) addRow(row);
        } else {
            if (removed > 0)
                for (int row = priorTop; row <= priorBottom; row++)
                    if (!priorMask[row - priorTop]
                            && (row < sharedTop || row > sharedBottom || mask[row - top]))
                        removeRow(row);
            if (added > 0)
                for (int row = top; row <= bottom; row++)
                    if (!mask[row - top]
                            && (row < sharedTop || row > sharedBottom || priorMask[row - priorTop]))
                        addRow(row);
        }
        priorTop = top;
        priorBottom = bottom;
        priorMask = mask;
        priorInk = kept + added;
        return columns;
    }

    private void addRow(int y) {
        if (rowDark[y] == 0) return;
        int[] dark = rowColumns[y];
        if (dark == null) {
            dark = new int[rowDark[y]];
            int count = 0, row = y * width;
            for (int x = 0; x < width; x++)
                if ((gray[row + x] & 255) < 170) dark[count++] = x;
            rowColumns[y] = dark;
        }
        for (int x : dark) columns[x]++;
    }

    private void removeRow(int y) {
        int[] dark = rowColumns[y];
        if (dark == null) return;
        for (int x : dark) columns[x]--;
    }
}
