// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Reject false tuplet timing in closed, optically verified beam groups. */
final class ClosedMixedBeamClock {
    private static final List<List<Integer>> SCALAR_BEAMS =
            List.of(List.of(1, 3), List.of(3, 3, 3), List.of(1, 3), List.of(3, 3, 3));
    private static final List<Integer> QUARTET = List.of(3, 3, 3);

    private ClosedMixedBeamClock() {}

    static ScorePageInterpretation restore(ScorePageInterpretation score, ScoreMeterChange initial,
            List<PlayingTechniqueDetector.Word> words, byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length) return score;
        var bars = score.measures();
        var notes = score.notes();
        var beams = score.beamSource();
        if (beams.noteOrigins().size() != notes.size()) return score;
        var rows = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var hasRest = new BitSet(bars.size());
        for (var rest : score.rests()) hasRest.set(rest.measureIndex());
        var meter = initial;
        int nextMeter = 0;
        List<ScoreNoteEvent> repaired = null;
        for (int i = 0; i < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            var bar = bars.get(i);
            var owners = rows.get(i);
            if (meter == null || meter.denominator() != 4 || hasRest.get(i) || bar.cadenceQuarterBeats() != 0
                    || !(meter.numerator() == 3 && owners.size() == 12
                            || meter.numerator() == 4 && (owners.size() == 14 || owners.size() == 16))) continue;
            owners.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var row = owners.stream().map(notes::get).toList();
            if (row.stream().anyMatch(n -> !ordinaryHead(n))) continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 10 || gap > 25) continue;
            if (row.size() == 16) {
                boolean closingDot = row.get(15).augmentationDots() == 1;
                if (!alternatingClock(row, closingDot)
                        || !provesAlternatingQuartets(gray, width, height, bar, row, gap)
                        || !provesAlternatingQuartets(raw, width, height, bar, row, gap)) continue;
                var changed = ClosedArpeggioTriplets.replaceGroups(beams, owners,
                        List.of(QUARTET, QUARTET, QUARTET, QUARTET), 0);
                if (changed == beams) continue;
                beams = changed;
                if (repaired == null) repaired = new ArrayList<>(notes);
                if (closingDot) {
                    repaired.set(owners.get(15), ClosedMonophonicSixteenths.withoutAugmentationDot(row.get(15)));
                } else {
                    for (int j = 9; j <= 11; j++)
                        repaired.set(owners.get(j), row.get(j).withTupletRatio(1, 1));
                }
                continue;
            }
            boolean scalar = row.size() == 14;
            var masks = scalar ? SCALAR_BEAMS : List.of(QUARTET);
            int limit = scalar ? row.size() : 4;
            if (!(scalar ? scalarClock(row) && fingeringThreeInRow(bar, row, words, width, height, gap) : openingFalseTriplet(row))
                    || !provesClock(gray, width, height, bar, row, masks, scalar, gap)
                    || !provesClock(raw, width, height, bar, row, masks, scalar, gap)) continue;
            var changed = ClosedArpeggioTriplets.replaceGroups(beams, owners.subList(0, limit), masks, 0);
            if (changed == beams) continue;
            beams = changed;
            if (repaired == null) repaired = new ArrayList<>(notes);
            if (scalar) {
                for (int j = 9; j <= 10; j++) {
                    var n = row.get(j);
                    repaired.set(owners.get(j), ClosedMonophonicSixteenths.withoutAugmentationDot(n)
                            .withArticulations(n.articulations() | NoteArticulation.STACCATO));
                }
                for (int j = 11; j < 14; j++)
                    repaired.set(owners.get(j), row.get(j).withTupletRatio(1, 1));
            } else {
                for (int j = 0; j < 3; j++)
                    repaired.set(owners.get(j), row.get(j).withTupletRatio(1, 1));
            }
        }
        return repaired == null ? score : score.withNotes(repaired,
                IntStream.range(0, notes.size()).boxed().toList()).withBeamSource(beams);
    }

    private static boolean ordinaryHead(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && Math.abs(n.stemDirection()) == 1 && n.unbeamedDurationBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0 && !n.tiedFromPrevious()
                && n.boundaryTies() == 0 && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && !n.crossStaffBeam() && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null;
    }

    private static boolean alternatingClock(List<ScoreNoteEvent> row, boolean closingDot) {
        int first = row.get(0).staffStep();
        for (int i = 0; i < row.size(); i++) {
            var note = row.get(i);
            if (note.staffStep() != first - i / 2 - 2 * (i % 2)
                    || note.stemDirection() != (closingDot || i < 8 ? -1 : 1)
                    || note.beamCount() != 2 || !ratio(note, !closingDot && i >= 9 && i <= 11)
                    || note.augmentationDots() != (closingDot && i == 15 ? 1 : 0)
                    || note.articulations() != 0
                    || note.octaveShift() != row.get(0).octaveShift()) return false;
        }
        return true;
    }

    private static boolean provesAlternatingQuartets(byte[] ink, int w, int h, MeasureRegion bar,
            List<ScoreNoteEvent> row, float gap) {
        if (!ClosedDottedSixteenths.hasClosingRule(ink, w, h, bar)) return false;
        float floor = bar.bottom() * h - 2.1f * gap;
        var track = StaffPitchTrack.forPrintedSlurs(ink, w, h, floor - 4 * gap, floor, gap);
        if (track == null) return false;
        var budget = new PrintedBeamShafts.Budget(405_000);
        var axes = new ArrayList<PrintedBeamShafts.Shaft>();
        float previousX = 0;
        for (int i = 0; i < row.size(); i++) {
            var n = row.get(i);
            float x = headX(bar, n, w);
            if (i > 0 && (x - previousX < 1.55f * gap || x - previousX > 2.65f * gap)) return false;
            var candidates = PrintedBeamShafts.at(ink, w, h, x, n.pageY() * h, gap, budget);
            if (candidates.isEmpty() && i == 9) axes.add(null);
            else if (candidates.size() == 1 && candidates.get(0).direction() == -n.stemDirection())
                axes.add(candidates.get(0));
            else return false;
            previousX = x;
        }
        var closing = row.get(15);
        float margin = closing.augmentationDots() == 0 ? .9f * gap : .5f * gap;
        if (budget.refused() || bar.right() * w - previousX < margin) return false;
        if (closing.augmentationDots() != 0
                && durationDot(ink, w, h, previousX, closing.pageY() * h, gap)) return false;
        if (axes.get(9) == null) {
            var head = row.get(9);
            var shaft = continuousStemBetween(ink, w, h, headX(bar, head, w), head.pageY() * h,
                    gap, axes.get(8), axes.get(10));
            if (shaft == null) return false;
            axes.set(9, shaft);
        }
        for (int i = 1; i < axes.size(); i++) {
            var left = axes.get(i - 1);
            var right = axes.get(i);
            if (!(i % 4 == 0 ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, w, h, bar, left, right, gap)
                    : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, w, h, left, right, gap, track)))
                return false;
        }
        return true;
    }

    /** Confirm a stem through the staff when its outer beam tip blends into neighboring ink. */
    private static PrintedBeamShafts.Shaft continuousStemBetween(byte[] ink, int w, int h, float x, float y,
            float gap, PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right) {
        float center = x + .56f * gap;
        float a = (left.left() + left.right()) * .5f, b = (right.left() + right.right()) * .5f;
        if (center <= a || center >= b || left.direction() != -1 || right.direction() != -1) return null;
        int tip = Math.round(left.endY() + (right.endY() - left.endY()) * ((center - a) / (b - a)));
        int first = Math.round(center - .18f * gap), last = Math.round(center + .18f * gap);
        int start = Math.round(y - .22f * gap);
        if (first < 0 || last >= w || tip < 0 || start >= h
                || start - tip < 2.5f * gap || start - tip > 5.3f * gap) return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        for (int col = first; col <= last; col++) {
            int holes = 0;
            for (int yy = tip; yy <= start; yy++)
                if ((ink[yy * w + col] & 255) >= 174) holes++;
            if (holes <= 2 && (ink[start * w + col] & 255) < 174
                    && (ink[tip * w + col] & 255) < 174) {
                if (!columns.isEmpty() && columns.get(columns.size() - 1).x() + 1 != col) return null;
                columns.add(new PrintedBeamShafts.Column(col, tip));
            }
        }
        if (columns.size() < 2 || columns.size() > Math.max(3, .30f * gap)) return null;
        return new PrintedBeamShafts.Shaft(columns.get(0).x(), columns.get(columns.size() - 1).x(),
                start, tip, -1, columns);
    }

    private static boolean scalarClock(List<ScoreNoteEvent> row) {
        int step = row.get(0).staffStep();
        for (int i = 0; i < row.size(); i++) {
            var n = row.get(i);
            if (n.stemDirection() != -1 || n.staffStep() != step + Math.min(i, 14 - i)
                    || n.octaveShift() != row.get(0).octaveShift()
                    || n.beamCount() != (i == 0 || i == 7 ? 1 : 2)
                    || n.augmentationDots() != (i == 9 || i == 10 ? 1 : 0)
                    || !ratio(n, i >= 11)) return false;
        }
        return true;
    }

    private static boolean fingeringThreeInRow(MeasureRegion bar, List<ScoreNoteEvent> row,
            List<PlayingTechniqueDetector.Word> words, int width, int height, float gap) {
        var four = row.get(8);
        var three = row.get(12);
        float fourX = headX(bar, four, width), threeX = headX(bar, three, width);
        return words.stream().anyMatch(a -> "4".equals(a.text())
                && Math.abs((a.left() + a.right()) * width * .5f - fourX) < .82f * gap
                && a.top() * height - four.pageY() * height > 4.2f * gap
                && words.stream().anyMatch(b -> "3".equals(b.text())
                        && Math.abs((b.left() + b.right()) * width * .5f - threeX) < .68f * gap
                        && Math.abs(a.top() - b.top()) * height < .27f * gap
                        && Math.abs(a.bottom() - b.bottom()) * height < .40f * gap));
    }

    private static boolean openingFalseTriplet(List<ScoreNoteEvent> row) {
        for (int i = 0; i < row.size(); i++) {
            var n = row.get(i);
            if (n.beamCount() != 2 || n.augmentationDots() != 0 || !ratio(n, i < 3)
                    || n.octaveShift() != row.get(0).octaveShift()
                    || n.stemDirection() != (i < 4 ? -1 : 1)
                    || i > 0 && (n.staffStep() > row.get(i - 1).staffStep()
                            || n.staffStep() < row.get(i - 1).staffStep() - 1)) return false;
        }
        return true;
    }

    private static boolean ratio(ScoreNoteEvent n, boolean triplet) {
        return n.tupletDivisor() == (triplet ? 3 : 1) && n.tupletNormalNotes() == (triplet ? 2 : 1);
    }

    private static boolean provesClock(byte[] ink, int w, int h, MeasureRegion bar,
            List<ScoreNoteEvent> row, List<List<Integer>> groups, boolean scalar, float gap) {
        if (!ClosedDottedSixteenths.hasClosingRule(ink, w, h, bar)) return false;
        float floor = bar.bottom() * h - 2.1f * gap;
        var track = StaffPitchTrack.forPrintedSlurs(ink, w, h, floor - 4 * gap, floor, gap);
        if (track == null) return false;
        var budget = new PrintedBeamShafts.Budget(380_000);
        var axes = new ArrayList<PrintedBeamShafts.Shaft>();
        float previousX = 0;
        for (int i = 0; i < row.size(); i++) {
            var n = row.get(i);
            float x = headX(bar, n, w), y = n.pageY() * h;
            if (i > 0 && (x - previousX < 1.55f * gap || x - previousX > 3.2f * gap)) return false;
            var candidates = PrintedBeamShafts.at(ink, w, h, x, y, gap, budget);
            if (candidates.size() != 1 || candidates.get(0).direction() != -n.stemDirection()) return false;
            axes.add(candidates.get(0));
            previousX = x;
            if (scalar && i >= 8 && !upperDot(ink, w, h, x, y, gap)) return false;
            if (scalar && (i == 9 || i == 10)
                    && durationDot(ink, w, h, x, y, gap)) return false;
            if (!scalar && i < 4 && !fingeringInk(ink, w, h, x, y, gap)) return false;
        }
        if (budget.refused() || bar.right() * w - previousX < .9f * gap) return false;
        int first = 0;
        for (var masks : groups) {
            if (first > 0 && !WrappedEighthTriplets.cutBetweenRecoveredGroups(
                    ink, w, h, bar, axes.get(first - 1), axes.get(first), gap)) return false;
            for (int mask : masks) {
                var left = axes.get(first);
                var right = axes.get(++first);
                if (!(mask == 1 ? WrappedEighthTriplets.matchesBeamRails(ink, w, h, left, right, gap, 1)
                        : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, w, h, left, right, gap, track)))
                    return false;
            }
            first++;
        }
        return scalar || WrappedEighthTriplets.cutBetweenRecoveredGroups(
                ink, w, h, bar, axes.get(3), axes.get(4), gap)
                && provesFollowingQuartet(ink, w, h, bar, axes, gap, track);
    }

    private static boolean provesFollowingQuartet(byte[] ink, int w, int h, MeasureRegion bar,
            List<PrintedBeamShafts.Shaft> axes, float gap, StaffPitchTrack track) {
        for (int i = 5; i < 8; i++) {
            if (!WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                    ink, w, h, axes.get(i - 1), axes.get(i), gap, track)) return false;
        }
        return WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, w, h, bar, axes.get(7), axes.get(8), gap);
    }

    private static float headX(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }

    private static boolean upperDot(byte[] ink, int w, int h, float x, float y, float gap) {
        return marks(ink, w, h, x, y, gap, -.74f, .74f, -1.92f, -.67f, false) == 1;
    }

    private static boolean durationDot(byte[] ink, int w, int h, float x, float y, float gap) {
        return marks(ink, w, h, x, y, gap, .63f, 1.52f, -.64f, .64f, false) != 0;
    }

    private static boolean fingeringInk(byte[] ink, int w, int h, float x, float y, float gap) {
        return marks(ink, w, h, x, y, gap, -.90f, .90f, -4.15f, -.69f, true) == 1;
    }

    private static long marks(byte[] ink, int w, int h, float x, float y, float gap,
            float left, float right, float top, float bottom, boolean numeral) {
        int l = Math.round(x + left * gap), r = Math.round(x + right * gap);
        int t = Math.round(y + top * gap), b = Math.round(y + bottom * gap);
        if (l < 0 || r >= w || t < 0 || b >= h) return -1;
        return RawNumeralComponents.capture(ink, w, h, l, t, r, b, () -> {}, () -> {})
                .components().stream().filter(c -> c.left > l && c.right < r && c.top > t && c.bottom < b)
                .filter(c -> {
                    int dx = c.right - c.left + 1, dy = c.bottom - c.top + 1;
                    return numeral
                            ? dx >= .41f * gap && dx <= 1.12f * gap && dy >= .75f * gap && dy <= 1.35f * gap
                                    && Math.abs((c.left + c.right) * .5f - x) <= .53f * gap
                            : Math.min(dx, dy) >= .23f * gap && Math.max(dx, dy) <= .63f * gap
                                    && Math.max(dx, dy) <= 1.55f * Math.min(dx, dy)
                                    && c.size() >= .50f * dx * dy;
                }).count();
    }
}
