// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Full-tile geometry of the stock segmentation model used on Android and Windows. */
final class SegmentationTiles {
    static final int WINDOW = 320;
    // Preserve the tuned overlap. It determines which tile owns the pixels at each seam.
    private static final int STEP = 192;

    private SegmentationTiles() {}

    static List<Integer> tileStarts(int size) {
        List<Integer> starts = new ArrayList<>();
        int finalStart = Math.max(0, size - WINDOW);
        for (int value = 0; value < size; value += STEP) {
            int start = Math.min(value, finalStart);
            if (starts.isEmpty() || starts.get(starts.size() - 1) != start) starts.add(start);
            if (start == finalStart) break;
        }
        if (starts.isEmpty()) starts.add(0);
        return starts;
    }

    // Identical geometry for every tile; retain unsigned weights when read.
    static byte[] edgeScores() {
        byte[] weights = new byte[WINDOW * WINDOW];
        for (int y = 0; y < WINDOW; y++)
            for (int x = 0; x < WINDOW; x++)
                weights[y * WINDOW + x] =
                        (byte)
                                (Math.min(Math.min(x, WINDOW - 1 - x), Math.min(y, WINDOW - 1 - y))
                                        + 1);
        return weights;
    }
}
