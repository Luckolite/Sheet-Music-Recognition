// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounds a crowded printed signature before two short runs and a closed dotted-half bar. */
final class TerminalTripleMeter {
    private TerminalTripleMeter() {}

    static MeterChangeDetector.Crop crop(
            MeterChangeDetector.Crop candidate,
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int bar,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes) {
        if (candidate == null
                || gray == null
                || labels == null
                || width < 1
                || height < 1
                || gray.length != (long) width * height
                || labels.length != gray.length
                || bar < 1
                || bar + 3 != measures.size()) return null;
        var opening = measures.get(bar);
        var previous = measures.get(bar - 1);
        var second = measures.get(bar + 1);
        var cadence = measures.get(bar + 2);
        float gap = candidate.gap();
        if (!Float.isFinite(gap)
                || gap < 8
                || gap > 32
                || !sameStaff(opening, previous, gap, height)
                || !sameStaff(opening, second, gap, height)
                || !sameStaff(opening, cadence, gap, height)) return null;
        var a = new ArrayList<ScoreNoteEvent>();
        var b = new ArrayList<ScoreNoteEvent>();
        ScoreNoteEvent finalNote = null;
        for (var note : notes) {
            int owner = note.measureIndex();
            if (owner == bar) a.add(note);
            if (owner == bar + 1) b.add(note);
            if (owner == bar + 2) {
                if (finalNote != null) return null;
                finalNote = note;
            }
        }
        if (!straightRun(a)
                || !straightRun(b)
                || finalNote == null
                || !plain(finalNote)
                || finalNote.unbeamedDurationBeats() != 2
                || finalNote.augmentationDots() != 1
                || finalNote.beamCount() != 0
                || finalNote.boundaryTies() != 0) return null;

        float right = cadence.right() * width;
        float hint = cadence.bottom() * height - 2.1f * gap;
        var close =
                PrintedDirectionStaff.printedRules(
                        gray, width, height, right - 2.5f * gap, hint, gap);
        if (close == null) return null;
        var terminal =
                new PlayingTechniqueDetector.Staff(
                        close[0] - 4 * close[1], close[0], close[1], 0, 1);
        if (terminal.top() < 0 || terminal.bottom() >= height) return null;
        if (PrintedCadenceDurations.boundaryColumn(
                        gray, width, terminal, right + close[1] * .35f, false, false)
                < 0) return null;

        float left = opening.left() * width;
        var rail =
                PrintedDirectionStaff.printedRules(
                        gray,
                        width,
                        height,
                        left + gap * 3,
                        opening.bottom() * height - gap * 2.1f,
                        gap);
        if (rail == null) return null;
        gap = rail[1];
        int top = Math.round(rail[0] - 4 * gap);
        int bottom = Math.round(rail[0]);
        int pad = Math.round(gap * .2f);
        int seam = Math.round((previous.right() + opening.left()) * width * .5f);
        int before = seam - Math.round(gap * .38f), after = seam + Math.round(gap * .38f);
        if (top < pad || bottom + pad >= height || before < 0 || after >= width) return null;
        int end = -1, first = -1;
        for (int x = before; x <= after; x++) {
            if (!fullRule(gray, width, x, top, gap)) continue;
            if (first < 0) first = x;
            if (end >= 0 && end + 1 != x) return null;
            end = x;
        }
        if (first < 0
                || end - first + 1 < 2
                || end - first + 1 > gap * .45f
                || DottedDyadMeasureSeparator.continuesBeyondStaff(
                        gray, width, height, (first + end) / 2, top, bottom, gap)) return null;
        int cropLeft = end + 2;
        var firstNote =
                a.stream()
                        .min(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .orElseThrow();
        float head = left + firstNote.positionInMeasure() * (opening.right() * width - left);
        int clip = Math.min(candidate.right(), Math.round(head - .85f * gap));
        int cropRight = separatorAfterDigits(gray, width, cropLeft, clip, top, gap);
        if (cropRight < 0) return null;
        var result =
                new MeterChangeDetector.Crop(
                        cropLeft, top - pad, cropRight, bottom + pad + 1, top, gap);
        return MeterChangeDetector.followingMeasure(result, width, height, measures) == bar
                        && MeterChangeDetector.precedesNotes(
                                result, labels, gray, width, height, bar, measures, notes)
                ? result
                : null;
    }

    private static boolean plain(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffCount() == 1
                && n.staffIndex() == 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.tupletDivisor() == 1
                && n.stemDirection() != 0
                && !n.crossStaffBeam()
                && !n.tiedFromPrevious()
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean straightRun(List<ScoreNoteEvent> notes) {
        return notes.size() == 12
                && notes.stream()
                        .allMatch(
                                n ->
                                        plain(n)
                                                && n.beamCount() == 2
                                                && n.augmentationDots() == 0
                                                && n.unbeamedDurationBeats() == 0);
    }

    private static boolean sameStaff(MeasureRegion a, MeasureRegion b, float gap, int height) {
        return Math.abs(a.top() - b.top()) * height < gap * .35f
                && Math.abs(a.bottom() - b.bottom()) * height < gap * .35f;
    }

    private static boolean fullRule(byte[] gray, int width, int x, int top, float gap) {
        for (int space = 0; space < 4; space++) {
            int first = Math.round(top + (space + .22f) * gap);
            int last = Math.round(top + (space + .80f) * gap);
            int hits = 0;
            for (int y = first; y <= last; y++) if ((gray[y * width + x] & 255) < 155) hits++;
            if (hits < (last - first + 1) * .86f) return false;
        }
        return true;
    }

    private static int separatorAfterDigits(
            byte[] gray, int width, int left, int limit, int top, float gap) {
        boolean hasGlyph = false;
        int empty = 0;
        for (int x = left; x < limit; x++) {
            int hits = 0;
            for (int space = 0; space < 4; space++) {
                int first = Math.round(top + (space + .22f) * gap);
                int last = Math.round(top + (space + .80f) * gap);
                for (int y = first; y <= last; y++) if ((gray[y * width + x] & 255) < 155) hits++;
            }
            if (hits > 1) {
                empty = 0;
                hasGlyph = true;
            } else if (hasGlyph && ++empty >= Math.max(2, Math.round(gap * .15f))) {
                return x + 1 - left >= gap * 1.2f && x + 1 - left <= gap * 2.3f ? x + 1 : -1;
            }
        }
        return -1;
    }
}
