// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fits original thin curve runs separated by independently proved accidental spines. */
final class AccidentalSlurCrossing {
    private static final int DARK = 120;
    private static final int EDGE = 154;

    private AccidentalSlurCrossing() {}

    static PrintedSlurContours.Bow find(
            byte[] gray, int width, StaffPitchTrack track, PrintedSlurOwners.Location a,
            PrintedSlurOwners.Location b, int accidental, float gap,
            int left, int top, int right, int bottom, long[] budget) {
        int cols = right - left + 1, rows = bottom - top + 1;
        long cost = 4L * cols * rows;
        if (budget[0] < cost || (accidental != 0 && accidental != 1)) return null;
        budget[0] -= cost;
        var spines = AccidentalSlurSpines.prove(gray, width, a.x(), b.x(), b.y(), gap, accidental);
        if (spines.size() != 2) return null;
        var first = spines.get(0);
        var second = spines.get(1);
        if (first.left() <= left || second.right() >= right
                || first.right() >= second.left()) return null;
        byte[] crop = new byte[cols * rows];
        Arrays.fill(crop, (byte) 255);
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) {
                if (inSpine(x, first) || inSpine(x, second)) continue;
                crop[(y - top) * cols + x - left] = gray[y * width + x];
            }
        var edge = fit(crop, cols, rows, left, top, track, a, b, gap, spines, EDGE);
        if (edge == null) return null;
        var dark = fit(crop, cols, rows, left, top, track, a, b, gap, spines, DARK);
        if (dark == null) return null;
        if (!sameTips(edge, dark)) {
            if (!overlappingBody(edge, dark, gap)) return null;
            var middle = fit(crop, cols, rows, left, top, track, a, b, gap, spines, 143);
            if (middle == null || !sameTips(edge, middle)) return null;
        }
        return witnessedCrossings(edge, gray, width, gap, spines) ? edge : null;
    }

    private static boolean inSpine(int x, AccidentalSlurSpines.Spine spine) {
        return x >= spine.left() && x <= spine.right();
    }

    private static PrintedSlurContours.Bow fit(
            byte[] crop, int width, int height, int left, int top, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap,
            List<AccidentalSlurSpines.Spine> spines, int cutoff) {
        var fragments = RawNumeralComponents.capture(
                crop, width, height, 0, 0, width - 1, height - 1, cutoff, () -> {}, () -> {});
        var first = spines.get(0);
        var second = spines.get(1);
        var starts = new ArrayList<List<PrintedSlurContours.ForeignRun>>();
        var middles = new ArrayList<List<PrintedSlurContours.ForeignRun>>();
        var ends = new ArrayList<List<PrintedSlurContours.ForeignRun>>();
        for (var f : fragments.components()) {
            if (f.left < 2 || f.right > width - 3 || f.top < 2 || f.bottom > height - 3) continue;
            int begin = left + f.left, end = left + f.right;
            List<List<PrintedSlurContours.ForeignRun>> candidates;
            if (end == first.left() - 1 && begin > a.x() - gap * .62f
                    && begin < a.x() + gap * .62f && end - begin >= gap * .40f)
                candidates = starts;
            else if (begin == first.right() + 1 && end == second.left() - 1)
                candidates = middles;
            else if (begin == second.right() + 1 && end > b.x() - gap * .64f
                    && end < b.x() + gap * .64f && end - begin >= gap * .40f)
                candidates = ends;
            else continue;
            var runs = DetachedLedgerSlurComponents.columns(f, width, gap, left, top);
            if (runs.isEmpty()) continue;
            if (candidates.size() == 4) return null;
            candidates.add(runs);
        }
        PrintedSlurContours.Bow result = null;
        for (var aSide : starts) for (var cSide : middles) for (var bSide : ends) {
            var cols = new ArrayList<>(aSide);
            cols.addAll(cSide);
            cols.addAll(bSide);
            var bow = PrintedSlurContours.fitPixelCells(cols, track);
            if (bow == null || !bow.above() || bow.asymmetric() || bow.rmsInGaps() > .06f
                    || bow.bendInGaps() < .33f || bow.bendInGaps() > 1.52f
                    || a.y() - bow.start().y() < gap * .63f || a.y() - bow.start().y() > gap * 1.95f
                    || b.y() - bow.end().y() < gap * .66f || b.y() - bow.end().y() > gap * 1.95f)
                continue;
            if (result != null) return null;
            result = bow;
        }
        return result;
    }

    private static boolean witnessedCrossings(
            PrintedSlurContours.Bow bow, byte[] gray, int width, float gap,
            List<AccidentalSlurSpines.Spine> spines) {
        int joins = 0;
        var points = bow.points();
        for (int i = 1; i < points.size(); i++) {
            var left = points.get(i - 1);
            var right = points.get(i);
            if (right.x() == left.x() + 1) continue;
            if (joins == spines.size()) return false;
            var spine = spines.get(joins++);
            if (left.x() != spine.left() - 1 || right.x() != spine.right() + 1
                    || Math.abs(left.y() - right.y()) > gap * .42f) return false;
            for (int x = left.x() + 1; x < right.x(); x++) {
                float fraction = (x - left.x()) / (float) (right.x() - left.x());
                int y = Math.round(left.y() + fraction * (right.y() - left.y()));
                boolean ink = false;
                for (int offset = -2; offset <= 2; offset++)
                    if ((gray[(y + offset) * width + x] & 255) < 148) ink = true;
                if (!ink) return false;
            }
        }
        return joins == 2;
    }

    private static boolean sameTips(PrintedSlurContours.Bow a, PrintedSlurContours.Bow b) {
        return Math.abs(a.start().x() - b.start().x()) <= 2
                && Math.abs(a.start().y() - b.start().y()) <= 2
                && Math.abs(a.end().x() - b.end().x()) <= 2
                && Math.abs(a.end().y() - b.end().y()) <= 2;
    }

    private static boolean overlappingBody(PrintedSlurContours.Bow edge, PrintedSlurContours.Bow dark,
            float gap) {
        if (Math.abs(edge.start().x() - dark.start().x()) > gap * .22f
                || Math.abs(edge.end().x() - dark.end().x()) > gap * .42f
                || dark.points().size() < edge.points().size() * .82f) return false;
        int common = 0, other = 0;
        for (var point : dark.points()) {
            while (other < edge.points().size() && edge.points().get(other).x() < point.x()) other++;
            if (other == edge.points().size()) break;
            var edgePoint = edge.points().get(other);
            if (edgePoint.x() != point.x()) continue;
            if (Math.abs(edgePoint.y() - point.y()) > 1.8f) return false;
            common++;
        }
        return common >= dark.points().size() * .94f;
    }
}
