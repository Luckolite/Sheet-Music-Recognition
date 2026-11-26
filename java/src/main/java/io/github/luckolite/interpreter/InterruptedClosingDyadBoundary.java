// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Retains a final beamed chord when its shaft was mistaken for the staff-end divider. */
final class InterruptedClosingDyadBoundary {
    private InterruptedClosingDyadBoundary() {}

    private record Chord(float x, float upper, float lower) {}

    static List<MeasureRegion> recover(List<MeasureRegion> measures, byte[] labels, byte[] gray,
            byte[] raw, int width, int height) {
        if (labels == null || gray == null || raw == null || labels.length != (long) width * height
                || gray.length != labels.length || raw.length != labels.length) return measures;
        List<MeasureRegion> result = null;
        for (int i = 0; i < measures.size(); i++) {
            var bar = measures.get(i);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 23 || bar.cadenceQuarterBeats() != 0
                    || (bar.right() - bar.left()) * width < 8 * gap) continue;
            if (i + 1 < measures.size() && Math.abs(measures.get(i + 1).top() - bar.top()) * height < gap) continue;
            int edge = Math.round(bar.right() * width);
            int printed = closingDivider(gray, width, height, bar, edge, gap);
            if (printed < 0 || printed != closingDivider(raw, width, height, bar, edge, gap)) continue;
            float floor = bar.bottom() * height - 2.05f * gap;
            var finalChord = chord(labels, width, height, edge - .35f * gap, printed - .24f * gap, floor, gap);
            var preceding = chord(labels, width, height, edge - 2.5f * gap, edge - .55f * gap, floor, gap);
            if (finalChord == null || preceding == null || finalChord.x() < edge + .45f * gap
                    || finalChord.x() - preceding.x() < 1.65f * gap || finalChord.x() - preceding.x() > 3.2f * gap
                    || Math.abs(finalChord.upper() - finalChord.lower() - preceding.upper() + preceding.lower()) > .24f * gap
                    || !beamed(gray, width, height, preceding, finalChord, edge, gap)
                    || !beamed(raw, width, height, preceding, finalChord, edge, gap)) continue;
            if (result == null) result = new ArrayList<>(measures);
            result.set(i, new MeasureRegion(bar.left(), printed / (float) width, bar.top(), bar.bottom()));
        }
        return result == null ? measures : List.copyOf(result);
    }

    private static int closingDivider(byte[] ink, int width, int height, MeasureRegion bar, int edge, float guess) {
        var rules = PrintedDirectionStaff.printedRules(ink, width, height, edge - 6.15f * guess,
                bar.bottom() * height - 2.05f * guess, guess);
        if (rules == null) return -1;
        float floor = rules[0], gap = rules[1], top = floor - 4 * gap;
        int first = edge + Math.round(1.37f * gap), last = edge + Math.round(2.45f * gap);
        int topStart = Math.round(top + .10f * gap), topEnd = Math.round(top + 1.21f * gap);
        int lowerStart = Math.round(floor - .74f * gap), lowerEnd = Math.round(floor - .10f * gap);
        if (first < 0 || last + gap >= width || topStart < 0 || lowerEnd >= height) return -1;
        int divider = -1, run = 0;
        for (int x = first; x <= last; x++) {
            boolean arms = darkRows(ink, width, x, topStart, topEnd) >= .91f * (topEnd - topStart + 1)
                    && darkRows(ink, width, x, lowerStart, lowerEnd) >= .91f * (lowerEnd - lowerStart + 1)
                    && darkRows(ink, width, x, Math.round(top + 1.67f * gap), Math.round(floor - 1.24f * gap)) <= 1;
            if (!arms) {
                run = 0;
                continue;
            }
            run++;
            if (run >= 2) {
                if (divider >= 0 && divider != x - 1) return -1;
                divider = x;
            }
        }
        if (divider < 0) return -1;
        int beyond = divider + Math.round(.65f * gap);
        for (int staff = 0; staff < 5; staff++) {
            int y = Math.round(top + staff * gap);
            if (darkRows(ink, width, beyond, y - 1, y + 1) != 0) return -1;
        }
        return divider;
    }

    private static int darkRows(byte[] ink, int width, int x, int top, int bottom) {
        int count = 0;
        for (int y = top; y <= bottom; y++) if ((ink[y * width + x] & 255) < 165) count++;
        return count;
    }

    private static Chord chord(byte[] labels, int width, int height, float left, float right, float floor, float gap) {
        int l = Math.round(left), r = Math.round(right);
        int top = Math.round(floor - 4.7f * gap), bottom = Math.round(floor + .2f * gap);
        if (l < 0 || r >= width || top < 0 || bottom >= height || r < l) return null;
        int w = r - l + 1, h = bottom - top + 1;
        byte[] binary = new byte[w * h];
        Arrays.fill(binary, (byte) 255);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (labels[(top + y) * width + l + x] == OmrMeasurePostProcessor.NOTEHEAD) binary[y * w + x] = 0;
        var parts = RawNumeralComponents.captureLongWindow(binary, w, h, 0, 0, w - 1, h - 1, 165)
                .components().stream().filter(c -> c.size() >= .58f * gap * gap)
                .sorted(java.util.Comparator.comparingInt(c -> c.top)).toList();
        if (parts.size() != 2) return null;
        var a = parts.get(0);
        var b = parts.get(1);
        float x1 = l + (a.left + a.right) * .5f, x2 = l + (b.left + b.right) * .5f;
        float upper = top + (a.top + a.bottom) * .5f, lower = top + (b.top + b.bottom) * .5f;
        return Math.abs(x1 - x2) <= .22f * gap && lower - upper >= 2.3f * gap && lower - upper <= 2.75f * gap
                ? new Chord((x1 + x2) * .5f, upper, lower) : null;
    }

    private static boolean beamed(byte[] ink, int width, int height, Chord previous, Chord last, int edge, float gap) {
        var budget = new PrintedBeamShafts.Budget(80_000);
        if (!FilledSparseBeamHead.completeInterior(ink, width, height, last.x(), last.upper(), gap)
                || !FilledSparseBeamHead.completeInterior(ink, width, height, last.x(), last.lower(), gap)) return false;
        var upper = PrintedBeamShafts.at(ink, width, height, last.x(), last.upper(), gap, budget);
        var lower = PrintedBeamShafts.at(ink, width, height, last.x(), last.lower(), gap, budget);
        var before = PrintedBeamShafts.at(ink, width, height, previous.x(), previous.lower(), gap, budget);
        if (budget.refused() || upper.size() != 1 || lower.size() != 1 || before.size() != 1) return false;
        var a = upper.get(0);
        var b = lower.get(0);
        return a.direction() == 1 && b.direction() == 1 && before.get(0).direction() == 1
                && Math.abs(a.left() - edge) <= .18f * gap && Math.abs(a.left() - b.left()) <= 2
                && Math.abs(a.endY() - b.endY()) <= 2
                && WrappedEighthTriplets.matchesBeamRails(ink, width, height, before.get(0), b, gap, 2);
    }
}
