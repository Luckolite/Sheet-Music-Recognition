// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Separates a slur between measured staff rules from its contact with a natural spine. */
final class NaturalRailSlurCrossing {
    private NaturalRailSlurCrossing() {}

    static PrintedSlurContours.Bow find(
            byte[] gray, int width, StaffPitchTrack track, PrintedSlurOwners.Location a,
            PrintedSlurOwners.Location b, float gap, int left, int top, int right, int bottom,
            long[] budget) {
        int cols = right - left + 1, rows = bottom - top + 1;
        if (width <= 0 || gray.length % width != 0 || gap < 10 || gap > 24
                || left < 0 || right >= width || top < 0 || bottom >= gray.length / width
                || cols <= 0 || rows <= 0 || (long) cols * rows > 8192
                || b.x() - a.x() < gap * 2f || b.x() - a.x() > gap * 3.3f
                || !track.verifiedAt(a.x()) || !track.verifiedAt(b.x())) return null;
        long cost = 4L * cols * rows;
        if (budget[0] < cost) return null;
        budget[0] -= cost;
        var spines = AccidentalSlurSpines.prove(gray, width, a.x(), b.x(), b.y(), gap, 0);
        if (spines.size() != 2) return null;
        var upperSpine = spines.get(0);
        var lowerSpine = spines.get(1);
        int spineCenter = (upperSpine.left() + upperSpine.right()) / 2;
        int lowerCenter = (lowerSpine.left() + lowerSpine.right()) / 2;
        int firstLowerRail = rail(track, a.x(), 3);
        int lastLowerRail = rail(track, b.x(), 3);
        if (a.y() - firstLowerRail < gap * .72f || a.y() - firstLowerRail > gap * 1.28f
                || b.y() - lastLowerRail < gap * .32f || b.y() - lastLowerRail > gap * .76f
                || Math.abs(upperSpine.top() - rail(track, spineCenter, 4)) > gap * .24f
                || rail(track, lowerCenter, 3) - lowerSpine.top() < gap * .18f
                || rail(track, lowerCenter, 3) - lowerSpine.top() > gap * .50f
                || !witnessedRail(gray, width, track, left, right, 4)
                || !witnessedRail(gray, width, track, left, right, 3)) return null;

        int spineRight = upperSpine.right();
        if (spineRight + 1 < lowerSpine.left() - 2
                && attachedFringe(gray, width, track, spineRight + 1, gap)) spineRight++;
        byte[] crop = new byte[cols * rows];
        Arrays.fill(crop, (byte) 255);
        for (int x = left; x <= right; x++) {
            if (x >= upperSpine.left() && x <= spineRight) continue;
            int from = Math.max(top, rail(track, x, 4) + 3);
            int through = Math.min(bottom, rail(track, x, 3) - 3);
            if (x > spineRight) through = Math.min(through, lowerSpine.top() - 2);
            for (int y = from; y <= through; y++) crop[(y - top) * cols + x - left] = gray[y * width + x];
        }
        var edge = fit(crop, cols, rows, left, top, track, a, b, gap, upperSpine.left(), spineRight, 154);
        if (edge == null) return null;
        var core = fit(crop, cols, rows, left, top, track, a, b, gap, upperSpine.left(), spineRight, 120);
        if (core == null || !sameTip(edge.start(), core.start()) || !sameTip(edge.end(), core.end()))
            return null;
        return witnessedSpineCrossing(edge, gray, width, gap, upperSpine.left(), spineRight) ? edge : null;
    }

    private static int rail(StaffPitchTrack track, float x, int index) {
        float[] sample = track.at(x);
        return Math.round(sample[0] - index * sample[1]);
    }

    private static boolean witnessedRail(
            byte[] gray, int width, StaffPitchTrack track, int left, int right, int index) {
        int height = gray.length / width, hits = 0, count = right - left + 1;
        int[] flanks = new int[2];
        for (int x = left; x <= right; x++) {
            int y = rail(track, x, index);
            if (y < 1 || y + 1 >= height) return false;
            if (!dark(gray, width, x, y, 1, 132)) continue;
            hits++;
            if (x - left < count / 4) flanks[0]++;
            if (right - x < count / 4) flanks[1]++;
        }
        return hits * 10 >= count * 9
                && Math.min(flanks[0], flanks[1]) * 5 >= (count / 4) * 4;
    }

    /** Permit a single proven dark fringe beyond the long spine at the same rule. */
    private static boolean attachedFringe(
            byte[] gray, int width, StaffPitchTrack track, int x, float gap) {
        int top = rail(track, x, 4) + 3, bottom = rail(track, x, 3) - 1, count = 0;
        for (int y = top; y <= bottom; y++) {
            if ((gray[y * width + x] & 255) < 145
                    && (gray[y * width + x - 1] & 255) < 145) count++;
        }
        return count >= gap * .66f;
    }

    private static PrintedSlurContours.Bow fit(
            byte[] crop, int width, int height, int left, int top, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap,
            int spineLeft, int spineRight, int threshold) {
        var graph = RawNumeralComponents.capture(
                crop, width, height, 0, 0, width - 1, height - 1, threshold, () -> {}, () -> {});
        var starts = new ArrayList<List<PrintedSlurContours.ForeignRun>>();
        var ends = new ArrayList<List<PrintedSlurContours.ForeignRun>>();
        for (var part : graph.components()) {
            if (part.left < 2 || part.right > width - 3 || part.top < 2 || part.bottom > height - 3)
                continue;
            int begin = left + part.left, end = left + part.right;
            List<List<PrintedSlurContours.ForeignRun>> target;
            if (end == spineLeft - 1 && Math.abs(begin - a.x()) < gap * .60f
                    && end - begin >= gap * .54f) target = starts;
            else if (begin == spineRight + 1 && Math.abs(end - b.x()) < gap * .63f
                    && end - begin >= gap * .74f) target = ends;
            else continue;
            var columns = DetachedLedgerSlurComponents.columns(part, width, gap, left, top);
            if (columns.isEmpty()) continue;
            if (target.size() >= 3) return null;
            target.add(columns);
        }
        PrintedSlurContours.Bow result = null;
        for (var start : starts) for (var end : ends) {
            var columns = new ArrayList<>(start);
            columns.addAll(end);
            var bow = PrintedSlurContours.fitPixelCells(columns, track);
            if (bow == null || !bow.above() || bow.asymmetric() || bow.rmsInGaps() > .045f
                    || bow.bendInGaps() < .30f || bow.bendInGaps() > .8f
                    || a.y() - bow.start().y() < gap * .92f || a.y() - bow.start().y() > gap * 1.48f
                    || b.y() - bow.end().y() < gap * .75f || b.y() - bow.end().y() > gap * 1.28f
                    || rail(track, bow.start().x(), 3) - bow.start().y() > gap * .24f)
                continue;
            if (result != null) return null;
            result = bow;
        }
        return result;
    }

    private static boolean witnessedSpineCrossing(
            PrintedSlurContours.Bow bow, byte[] gray, int width, float gap, int left, int right) {
        int joins = 0;
        int height = gray.length / width;
        var points = bow.points();
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            if (b.x() == a.x() + 1) continue;
            if (++joins != 1 || a.x() != left - 1 || b.x() != right + 1
                    || Math.abs(a.y() - b.y()) > gap * .35f) return false;
            for (int x = left; x <= right; x++) {
                float fraction = (x - a.x()) / (float) (b.x() - a.x());
                int y = Math.round(a.y() + fraction * (b.y() - a.y()));
                if (y < 2 || y + 2 >= height || !dark(gray, width, x, y, 2, 130)) return false;
            }
        }
        return joins == 1;
    }

    private static boolean sameTip(PrintedSlurContours.Point a, PrintedSlurContours.Point b) {
        return Math.abs(a.x() - b.x()) <= 2 && Math.abs(a.y() - b.y()) <= 2;
    }

    private static boolean dark(byte[] gray, int width, int x, int y, int radius, int threshold) {
        for (int dy = -radius; dy <= radius; dy++)
            if ((gray[(y + dy) * width + x] & 255) < threshold) return true;
        return false;
    }
}
