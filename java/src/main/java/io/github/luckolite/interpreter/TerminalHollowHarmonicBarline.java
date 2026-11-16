// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** A pair of hollow heads can make their shared quarter shaft look like a short barline. */
final class TerminalHollowHarmonicBarline {
    private TerminalHollowHarmonicBarline() {}

    private record Shaft(int x, int lower, int upper) {}
    private record Evidence(Shaft seam, Shaft second) {}

    static List<MeasureRegion> join(
            List<MeasureRegion> measures, byte[] labels, byte[] normalized, byte[] source, int width, int height) {
        if (measures.size() < 4 || source == null || normalized == null || labels == null
                || width < 1 || height < 1 || source.length != (long) width * height
                || normalized.length != source.length || labels.length != source.length) return measures;
        int end = measures.size();
        int row = end - 1;
        var finalRegion = measures.get(row);
        float nominal = (finalRegion.bottom() - finalRegion.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 25) return measures;
        while (row > 0 && sameStaff(measures.get(row - 1), finalRegion, nominal, height)) row--;
        // A closed single-quarter ending and the two-attack bar at a new staff
        // have independent measure boundaries that can disambiguate the seam.
        if (end - row < 4) return measures;

        var patched = new ArrayList<MeasureRegion>(measures.size());
        for (int i = 0; i < end; i++) {
            var first = measures.get(i);
            if (i != row && i != end - 2) {
                patched.add(first);
                continue;
            }
            var next = measures.get(i + 1);
            boolean terminal = i == end - 2;
            var previous = terminal ? measures.get(i - 1) : null;
            var following = terminal ? null : measures.get(i + 2);
            var original = prove(previous, first, next, following, labels, source, width, height);
            var geometry = prove(previous, first, next, following, labels, normalized, width, height);
            if (original == null || geometry == null
                    || !agrees(original.seam, geometry.seam)
                    || (!terminal && !agrees(original.second, geometry.second))) {
                patched.add(first);
                continue;
            }
            patched.add(new MeasureRegion(first.left(), next.right(), Math.min(first.top(), next.top()),
                    Math.max(first.bottom(), next.bottom()), terminal ? 1 : 0));
            i++;
        }
        return patched.size() == measures.size() ? measures : List.copyOf(patched);
    }

    private static boolean sameStaff(MeasureRegion first, MeasureRegion last, float gap, int height) {
        return Math.abs(first.top() - last.top()) * height <= gap * .28f
                && Math.abs(first.bottom() - last.bottom()) * height <= gap * .28f;
    }

    private static boolean agrees(Shaft first, Shaft second) {
        return Math.abs(first.x - second.x) <= 1 && first.lower == second.lower && first.upper == second.upper;
    }

    private static Evidence prove(
            MeasureRegion previous, MeasureRegion first, MeasureRegion next, MeasureRegion following,
            byte[] labels, byte[] ink, int width, int height) {
        boolean terminal = following == null;
        float nominal = (first.bottom() - first.top()) * height / 8.2f;
        float left = first.left() * width, right = next.right() * width;
        float a = first.right() * width, b = next.left() * width;
        float mid = (a + b) * .5f;
        if (nominal < 10 || nominal > 25 || !sameStaff(first, next, nominal, height)
                || first.cadenceQuarterBeats() != 0 || next.cadenceQuarterBeats() != 0
                || a - left < nominal * 2.3f || a - left > nominal * 4.8f
                || b - a < nominal * .8f || b - a > nominal * 1.4f
                || right - b < nominal * 3.2f
                || right - b > nominal * (terminal ? 4.5f : 7.5f)) return null;
        var frame = PrintedDirectionStaff.printedRules(ink, width, height, mid + nominal * 1.15f,
                first.bottom() * height - nominal * 2.1f, nominal);
        if (frame == null || Math.abs(frame[1] - nominal) > nominal * .12f) return null;
        float gap = frame[1];
        var staff = new PlayingTechniqueDetector.Staff(frame[0] - 4 * gap, frame[0], gap, 0, 1);
        var seam = shaft(labels, ink, width, height, staff, a, b);
        if (seam == null) return null;
        float headX = seam.x - gap * .68f;
        if (terminal) {
            int finalRule = PrintedCadenceDurations.boundaryColumn(
                    ink, width, staff, right + gap * .85f, false, true);
            if (finalRule < 0 || !sameStaff(previous, first, gap, height)
                    || PrintedCadenceDurations.boundaryColumn(ink, width, staff,
                            (previous.right() * width + left) * .5f, true, false) < 0
                    || !PrintedCadenceDurations.emptyOutsideChord(
                            ink, width, staff, left, finalRule, headX, headX, false)) return null;
            return new Evidence(seam, null);
        }
        if (!sameStaff(next, following, gap, height)) return null;
        int close = PrintedCadenceDurations.boundaryColumn(ink, width, staff,
                (right + following.left() * width) * .5f, true, false);
        if (close < 0) return null;
        var second = shaft(labels, ink, width, height, staff, seam.x + gap * 3.65f, seam.x + gap * 4.8f);
        if (second == null) return null;
        float secondHead = second.x - gap * .68f;
        if (!PrintedCadenceDurations.emptyOutsideChord(
                        ink, width, staff, left, secondHead - gap * .5f, headX, headX, false)
                || !PrintedCadenceDurations.emptyOutsideChord(
                        ink, width, staff, headX + gap * .75f, close, secondHead, secondHead, false))
            return null;
        return new Evidence(seam, second);
    }

    private static Shaft shaft(byte[] labels, byte[] ink, int width, int height,
            PlayingTechniqueDetector.Staff staff, float left, float right) {
        float gap = staff.gap();
        int top = Math.round(staff.top()), bottom = Math.round(staff.bottom());
        int extension = Math.max(2, Math.round(gap * .12f));
        if (top - gap * 2 < 0 || bottom + gap >= height || left < gap * 3 || right + gap * 2 >= width)
            return null;
        int first = -1, last = -1;
        for (int x = (int)Math.ceil(left); x <= (int)Math.floor(right); x++) {
            int dark = 0;
            for (int y = top - extension; y <= bottom; y++)
                if ((ink[y * width + x] & 255) < 155) dark++;
            if (dark < (bottom - top + extension + 1) * .93f
                    || (ink[(top - extension) * width + x] & 255) >= 155) continue;
            if (first < 0) first = x;
            else if (x != last + 1) return null;
            last = x;
        }
        if (first < 0 || last - first + 1 > gap * .29f) return null;
        for (int x = first; x <= last; x++) {
            int mask = 0;
            float headX = x - gap * .68f;
            for (int step = -2; step <= 6; step++) {
                float y = staff.bottom() - step * gap * .5f;
                if (ArtificialHarmonics.symbolDiamond(labels, ink, width, height, headX, y, gap)
                        && ArtificialHarmonics.enclosedCounter(
                                ink, width, height, headX, y, gap * .82f, gap * .38f)
                        && ArtificialHarmonics.shaftCrossedDiamond(
                                ink, width, height, headX, y, gap, x, 1, true)) mask |= 1 << (step + 2);
            }
            Shaft chosen = null;
            for (int down = 0; down <= 6; down++) {
                if ((mask & 1 << down) == 0) continue;
                for (int up = down + 2; up <= Math.min(8, down + 4); up++) {
                    if ((mask & 1 << up) == 0) continue;
                    if (chosen != null) return null;
                    chosen = new Shaft(x, down - 2, up - 2);
                }
            }
            if (chosen == null) continue;
            float upperY = staff.bottom() - chosen.upper * gap * .5f;
            if (ArtificialHarmonics.unflaggedQuarterTip(ink, width, height, x, upperY,
                    staff.bottom() - chosen.lower * gap * .5f, top - extension, 1, staff)) return chosen;
        }
        return null;
    }
}
