// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Measures the two surviving sides of a downstem interrupted on both sides of a staff rule. */
final class RuledOpeningDownshaft {
    private RuledOpeningDownshaft() {}

    static PrintedBeamShafts.Shaft measure(byte[] gray, int width, int height, MeasureRegion bar,
            float x, float y, float gap) {
        if (gray == null || width < 1 || height < 1 || gray.length != (long) width * height
                || !Float.isFinite(x + y + gap) || gap < 10 || gap > 25) return null;
        int start = Math.round(y + gap * .20f), stop = Math.round(y + gap * 3.65f);
        int first = (int) Math.ceil(x - gap * .83f);
        int last = (int) Math.floor(x - gap * .43f);
        int reach = Math.round(gap * 1.15f);
        if (first - reach < 0 || last + reach >= width || start < 0 || stop >= height) return null;

        var track = WrappedMeasureBoundaries.closedTripletStaff(gray, width, height, bar);
        if (track == null || !track.verifiedAt(x)) return null;
        var staff = track.at(x);
        int rule = Math.round((staff[0] - y - gap * 1.45f) / staff[1]);
        if (rule < 0 || rule >= 5) return null;
        int rowGuess = Math.round(staff[0] - rule * staff[1]);
        int band = Math.round(gap * .25f);
        int inner = Math.round(gap * .48f), outer = Math.round(gap * .96f);
        int middle = (first + last) / 2;
        int ruleTop = stop, ruleBottom = start, ruleRows = 0;
        for (int row = rowGuess - band; row <= rowGuess + band; row++) {
            if (row < start || row > stop) return null;
            int n = outer - inner + 1;
            int before = darkCount(gray, width, row, middle - outer, middle - inner);
            int after = darkCount(gray, width, row, middle + inner, middle + outer);
            if (Math.min(before, after) < n * .72f) continue;
            ruleTop = Math.min(ruleTop, row);
            ruleBottom = row;
            ruleRows++;
        }
        if (ruleRows == 0 || ruleRows > gap * .30f || ruleBottom - ruleTop + 1 != ruleRows) return null;

        var columns = new ArrayList<PrintedBeamShafts.Column>();
        var joins = new ArrayList<Integer>();
        for (int col = first; col <= last; col++) {
            int head = start;
            while (head < ruleTop && dark(gray, width, col, head)) head++;
            if (head - start < gap * .37f || head - y > gap * .88f
                    || ruleTop - head < gap * .38f || !dark(gray, width, col, ruleBottom)
                    || darkCountColumn(gray, width, col, head, ruleTop - 2) != 0) continue;
            int contact = ruleBottom;
            while (contact < ruleBottom + 2 && dark(gray, width, col, contact + 1)) contact++;
            int resumes = contact + 1;
            while (resumes <= ruleBottom + gap * .80f && !dark(gray, width, col, resumes)) resumes++;
            if (resumes - contact < gap * .23f || resumes - ruleBottom > gap * .80f) continue;
            int end = resumes;
            while (end < stop && dark(gray, width, col, end + 1)) end++;
            if (end == stop || end - resumes < gap * .85f || end - resumes > gap * 1.43f) continue;
            columns.add(new PrintedBeamShafts.Column(col, end));
            joins.add(resumes);
        }
        if (columns.size() != 2 || columns.get(1).x() - columns.get(0).x() != 1
                || Math.abs(columns.get(1).endY() - columns.get(0).endY()) > 2
                || Math.abs(joins.get(1) - joins.get(0)) > 3) return null;
        int from = columns.get(0).x(), to = columns.get(1).x();
        int joined = Math.max(joins.get(0), joins.get(1));
        for (int row = joined; row <= joined + Math.round(gap * .22f); row++)
            if (darkCount(gray, width, row, from - inner, to + inner) > gap * .39f) return null;
        return new PrintedBeamShafts.Shaft(from, to, start,
                Math.min(columns.get(0).endY(), columns.get(1).endY()), 1, List.copyOf(columns));
    }

    /** Extends the next shaft across a short break immediately above the measured bottom rule. */
    static PrintedBeamShafts.Shaft continueNeighbor(byte[] gray, int width, int height, MeasureRegion bar,
            float x, float y, float gap, PrintedBeamShafts.Shaft measured) {
        int tip = measured.endY();
        if (measured.direction() != 1 || tip - y < gap * 1.9f || tip - y > gap * 2.65f) return null;
        var track = WrappedMeasureBoundaries.closedTripletStaff(gray, width, height, bar);
        if (track == null || !track.verifiedAt(x)) return null;
        float floor = track.at(x)[0];
        int top = tip - Math.round(gap * .47f), stop = Math.round(y + gap * 4.32f);
        int first = measured.left(), last = Math.min(first + 2, (int) Math.floor(x - gap * .46f));
        if (top < 0 || stop >= height || first < 0 || last >= width) return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        for (int col = first; col <= last; col++) {
            int firstBlank = tip - 2;
            if (!dark(gray, width, col, firstBlank)
                    || darkCountColumn(gray, width, col, top, firstBlank) < (firstBlank - top + 1) * .65f) continue;
            while (firstBlank <= tip + 3 && dark(gray, width, col, firstBlank)) firstBlank++;
            if (firstBlank > tip + 3) continue;
            int resumed = firstBlank;
            while (resumed < firstBlank + gap * .55f && !dark(gray, width, col, resumed)) resumed++;
            if (resumed - firstBlank < gap * .18f || resumed - firstBlank > gap * .55f
                    || !dark(gray, width, col, resumed)
                    || resumed > floor - gap * .12f || resumed < floor - gap * .48f) continue;
            int end = resumed;
            while (end < stop && dark(gray, width, col, end + 1)) end++;
            if (end == stop || end - resumed < gap * .95f || end - resumed > gap * 1.52f) continue;
            columns.add(new PrintedBeamShafts.Column(col, end));
        }
        if (columns.size() != 2 || columns.get(1).x() - columns.get(0).x() != 1
                || Math.abs(columns.get(1).endY() - columns.get(0).endY()) > 1) return null;
        return new PrintedBeamShafts.Shaft(columns.get(0).x(), columns.get(1).x(), measured.startY(),
                Math.min(columns.get(0).endY(), columns.get(1).endY()), 1, List.copyOf(columns));
    }

    private static int darkCount(byte[] gray, int width, int row, int first, int last) {
        int count = 0;
        for (int col = first; col <= last; col++) if (dark(gray, width, col, row)) count++;
        return count;
    }

    private static int darkCountColumn(byte[] gray, int width, int col, int first, int last) {
        int count = 0;
        for (int row = first; row <= last; row++) if (dark(gray, width, col, row)) count++;
        return count;
    }

    private static boolean dark(byte[] gray, int width, int col, int row) {
        return (gray[row * width + col] & 255) < 165;
    }
}
