// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Binds a downward shaft separated from both sides of an upper ledger line. */
final class LedgerInterruptedDownshaft {
    private LedgerInterruptedDownshaft() {}

    static PrintedBeamShafts.Shaft measure(byte[] gray, int width, int height,
            MeasureRegion bar, float x, float y, float gap) {
        if (gap < 12 || gap > 24) return null;
        var staff = WrappedMeasureBoundaries.closedTripletStaff(gray, width, height, bar);
        if (staff == null || !staff.verifiedAt(Math.round(x))) return null;
        var frame = staff.at(x);
        int rule = Math.round((frame[0] - y) / frame[1]) - 1;
        if (rule < 5 || rule > 6) return null;
        int row = Math.round(frame[0] - rule * frame[1]);
        if (row - y < .82f * gap || row - y > 1.12f * gap) return null;
        int first = Math.round(x - .73f * gap);
        int last = Math.round(x - .45f * gap);
        int start = Math.round(y + .28f * gap);
        int limit = Math.round(y + 4.75f * gap);
        if (first - 5 < 0 || last + Math.round(gap * .9f) >= width || start < 0 || limit >= height)
            return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        for (int col = first; col <= last; col++) {
            int upperEnd = row - Math.round(.30f * gap);
            int lowerStart = row + Math.round(.24f * gap);
            if (!ink(gray, width, col, upperEnd) || !ink(gray, width, col, start)
                    || ink(gray, width, col, row - 3) || ink(gray, width, col, row + 2)
                    || !ink(gray, width, col, lowerStart)) continue;
            int end = lowerStart;
            while (end < limit && ink(gray, width, col, end + 1)) end++;
            if (end - lowerStart < gap * 2.8f
                    || end - y < gap * 3.9f || end == limit) continue;
            columns.add(new PrintedBeamShafts.Column(col, end));
        }
        if (columns.size() != 2) return null;
        var a = columns.get(0);
        var b = columns.get(1);
        if (b.x() != a.x() + 1 || Math.abs(a.endY() - b.endY()) > 2
                || !ruleRun(gray, width, row, a.x() - 4, a.x() - 1)
                || !ruleRun(gray, width, row, b.x() + 3, b.x() + Math.round(gap * .78f))) return null;
        return new PrintedBeamShafts.Shaft(a.x(), b.x(), start, Math.min(a.endY(), b.endY()),
                1, List.copyOf(columns));
    }

    private static boolean ruleRun(byte[] gray, int width, int row, int first, int last) {
        for (int col = first; col <= last; col++)
            if (!ink(gray, width, col, row) || !ink(gray, width, col, row + 1)) return false;
        return true;
    }

    private static boolean ink(byte[] gray, int width, int col, int row) {
        return (gray[row * width + col] & 255) < 150;
    }
}
