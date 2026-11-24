// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Joins measured slur strokes on opposite sides of the uppermost printed staff line. */
final class UpperRuleSlurCrossing {
    private UpperRuleSlurCrossing() {}

    static PrintedSlurContours.Bow find(
            byte[] gray, int width, StaffPitchTrack track, PrintedSlurOwners.Location a,
            PrintedSlurOwners.Location b, float gap,
            int left, int top, int right, int bottom, long[] budget) {
        if (!track.verifiedAt(a.x()) || !track.verifiedAt(b.x())
                || b.x() - a.x() > gap * 2.25f) return null;
        float[] aFrame = track.at(a.x()), bFrame = track.at(b.x());
        float aDepth = a.y() - (aFrame[0] - 4 * aFrame[1]);
        float bDepth = b.y() - (bFrame[0] - 4 * bFrame[1]);
        if (aDepth < gap * .9f || aDepth > gap * 1.7f
                || bDepth < gap * .55f || bDepth > gap * 1.35f) return null;
        int cols = right - left + 1;
        int rows = bottom - top + 1;
        long cost = 3L * cols * rows;
        if (budget[0] < cost) return null;
        budget[0] -= cost;
        int[] levels = new int[cols];
        byte[] window = new byte[cols * rows];
        Arrays.fill(window, (byte) 255);
        int paperBelow = Math.round(gap * .54f);
        int lineHits = 0;
        for (int x = left; x <= right; x++) {
            int col = x - left;
            float[] slice = track.at(x);
            int y = Math.round(slice[0] - 4 * slice[1]);
            if (y < 2 || (y + 2) * width + x >= gray.length) return null;
            levels[col] = y;
            if (dark(gray, width, x, y, 1, 150)) lineHits++;
            for (int row = top; row <= Math.min(bottom, y + paperBelow); row++)
                if (Math.abs(row - y) >= 3)
                    window[(row - top) * cols + col] = gray[row * width + x];
        }
        if (lineHits * 10 < cols * 9) return null;
        var strong = fit(window, cols, rows, left, top, levels, track, a, b, gap, 120);
        if (strong == null) return null;
        var edge = fit(window, cols, rows, left, top, levels, track, a, b, gap, 154);
        if (edge == null || Math.abs(strong.start().x() - edge.start().x()) > 1
                || Math.abs(strong.end().x() - edge.end().x()) > 2
                || Math.abs(strong.start().y() - edge.start().y()) > 2
                || Math.abs(strong.end().y() - edge.end().y()) > 2) return null;
        var points = edge.points();
        int joins = 0;
        for (int i = 1; i < points.size(); i++) {
            var before = points.get(i - 1);
            var after = points.get(i);
            int dx = after.x() - before.x();
            if (dx == 1) continue;
            if (++joins != 1 || dx > gap * .27f + 1) return null;
            for (int x = before.x() + 1; x < after.x(); x++) {
                int y = Math.round(before.y() + (after.y() - before.y())
                        * (x - before.x()) / dx);
                if (Math.abs(y - levels[x - left]) > 3
                        || !dark(gray, width, x, y, 1, 145)) return null;
            }
        }
        return joins == 1 ? edge : null;
    }

    private static PrintedSlurContours.Bow fit(
            byte[] window, int width, int height, int x0, int y0, int[] rail,
            StaffPitchTrack track, PrintedSlurOwners.Location a, PrintedSlurOwners.Location b,
            float gap, int threshold) {
        var graph = RawNumeralComponents.capture(
                window, width, height, 0, 0, width - 1, height - 1, threshold, () -> {}, () -> {});
        var starters = new ArrayList<List<PrintedSlurContours.ForeignRun>>();
        var arches = new ArrayList<List<PrintedSlurContours.ForeignRun>>();
        for (var part : graph.components()) {
            if (part.left < 2 || part.right >= width - 2 || part.top < 2
                    || part.bottom >= height - 2 || part.right - part.left < 2) continue;
            int l = x0 + part.left, r = x0 + part.right;
            int center = (part.left + part.right) / 2;
            List<List<PrintedSlurContours.ForeignRun>> group;
            if (part.top + y0 >= rail[center] + 2
                    && Math.abs(l - a.x()) <= gap * .58f && r - l <= gap * .78f) {
                group = starters;
            } else if (part.bottom + y0 <= rail[center] - 2
                    && Math.abs(r - b.x()) <= gap * .60f && r - l >= gap * 1.33f) {
                group = arches;
            } else continue;
            var runs = DetachedLedgerSlurComponents.columns(part, width, gap, x0, y0);
            if (runs.isEmpty()) continue;
            if (group.size() >= 4) return null;
            group.add(runs);
        }
        PrintedSlurContours.Bow accepted = null;
        for (var start : starters) for (var arch : arches) {
            var l = start.get(start.size() - 1);
            var r = arch.get(0);
            int dx = r.x() - l.x();
            if (dx <= 1 || dx > gap * .27f + 1) continue;
            var combined = new ArrayList<>(start);
            combined.addAll(arch);
            var bow = PrintedSlurContours.fitPixelCells(combined, track);
            if (bow == null || !bow.above() || bow.asymmetric() || bow.rmsInGaps() > .045f
                    || bow.bendInGaps() < .36f || bow.bendInGaps() > 1f
                    || a.y() - bow.start().y() < gap * .72f
                    || a.y() - bow.start().y() > gap * 1.6f
                    || b.y() - bow.end().y() < gap * .8f
                    || b.y() - bow.end().y() > gap * 1.75f) continue;
            if (accepted != null) return null;
            accepted = bow;
        }
        return accepted;
    }

    private static boolean dark(byte[] gray, int width, int x, int y, int radius, int threshold) {
        if (y < radius || (y + radius) * width + x >= gray.length) return false;
        for (int dy = -radius; dy <= radius; dy++)
            if ((gray[(y + dy) * width + x] & 255) < threshold) return true;
        return false;
    }
}
