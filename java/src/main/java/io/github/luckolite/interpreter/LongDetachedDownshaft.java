// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Proves the thin, detached downstem at the start of a closing beam group. */
final class LongDetachedDownshaft {
    private LongDetachedDownshaft() {}

    static PrintedBeamShafts.Shaft measure(byte[] ink, int width, int height,
            float x, float y, float gap) {
        if (gap < 12 || gap > 24) return null;
        int left = Math.round(x - .75f * gap), right = Math.round(x - .47f * gap);
        int above = Math.round(y + .39f * gap), below = Math.round(y + .79f * gap);
        int limit = Math.round(y + 7.65f * gap);
        if (left < 1 || right + 1 >= width || above < 0 || limit + 2 >= height) return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        int first = -1;
        for (int col = left; col <= right; col++) {
            if (!dark(ink, width, col, above)) continue;
            int seam = above + 1;
            while (seam <= below && dark(ink, width, col, seam)) seam++;
            int start = seam;
            while (start <= below && !dark(ink, width, col, start)) start++;
            if (start > below || start - seam < 3 || start - seam > 5) continue;
            int end = start;
            while (end < limit && dark(ink, width, col, end + 1)) end++;
            if (end == limit || end - y < 6.4f * gap
                    || dark(ink, width, col, end + 2)) continue;
            int supported = 0;
            int sampleEnd = start + Math.round(2.5f * gap);
            for (int row = start; row <= sampleEnd; row++)
                if (dark(ink, width, col - 1, row) || dark(ink, width, col + 1, row)) supported++;
            if (supported < (sampleEnd - start + 1) * .78f) continue;
            if (first == -1) first = start;
            if (start != first) return null;
            columns.add(new PrintedBeamShafts.Column(col, end));
        }
        if (columns.size() != 1) return null;
        var shaft = columns.get(0);
        return new PrintedBeamShafts.Shaft(shaft.x(), shaft.x(), first, shaft.endY(), 1, List.copyOf(columns));
    }

    private static boolean dark(byte[] ink, int width, int x, int y) {
        return (ink[y * width + x] & 255) < 150;
    }
}
