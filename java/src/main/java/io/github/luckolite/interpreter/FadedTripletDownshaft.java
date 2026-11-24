// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Finds a faded downstem only when two adjacent columns retain head-side contrast and a shared tip. */
final class FadedTripletDownshaft {
    private FadedTripletDownshaft() {}

    static PrintedBeamShafts.Shaft measure(byte[] ink, int width, int height,
            float x, float y, float gap) {
        if (gap < 12 || gap > 22) return null;
        int from = Math.round(x - .73f * gap), to = Math.round(x - .49f * gap);
        int head = Math.round(y + .45f * gap), contrastEnd = Math.round(y + 2.22f * gap);
        int tipMin = Math.round(y + 3.10f * gap), limit = Math.round(y + 5.35f * gap);
        if (from < 3 || to + 3 >= width || head < 0 || limit >= height) return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        for (int col = from; col <= to; col++) {
            int support = 0;
            for (int row = head + 1; row <= contrastEnd; row++) {
                int offset = row * width + col;
                int flank = Math.min(ink[offset - 3] & 255, ink[offset + 3] & 255);
                if ((ink[offset] & 255) + 40 < flank) support++;
            }
            if (support < Math.round(.78f * gap)) continue;
            int begin = -1, end = -1;
            int best = -1;
            for (int row = head; row <= limit; row++) {
                if ((ink[row * width + col] & 255) >= 170) continue;
                if (end < row - 3) begin = row;
                end = row;
                if (end >= tipMin && end - begin >= 1.16f * gap) best = end;
            }
            if (best >= 0 && best < limit - 2)
                columns.add(new PrintedBeamShafts.Column(col, best));
        }
        if (columns.size() < 2 || columns.size() > 3) return null;
        var first = columns.get(0);
        var last = columns.get(columns.size() - 1);
        if (last.x() - first.x() + 1 != columns.size()) return null;
        int end = columns.stream().mapToInt(PrintedBeamShafts.Column::endY).min().orElseThrow();
        if (columns.stream().anyMatch(c -> c.endY() > end + 2)) return null;
        return new PrintedBeamShafts.Shaft(first.x(), last.x(), head, end, 1, List.copyOf(columns));
    }
}
