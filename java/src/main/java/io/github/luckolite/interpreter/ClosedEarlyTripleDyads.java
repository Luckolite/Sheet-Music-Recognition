// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0

package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Performs two complete short beamed bars before the following written 3/4 header. */
final class ClosedEarlyTripleDyads {
    private ClosedEarlyTripleDyads() {}

    static void apply(
            ScorePageInterpretation score,
            ScoreMeterChange initialMeter,
            byte[] gray,
            int w,
            int h,
            float[] beats) {
        if (gray == null || gray.length != (long) w * h || score.measures().size() != beats.length)
            return;
        for (int first = 1; first + 3 < beats.length; first++) {
            if (beats[first - 1] != 2f
                    || beats[first] != 4f
                    || beats[first + 1] != 4f
                    || beats[first + 2] != 1f) continue;
            if (!inCommonTime(score.meterChanges(), initialMeter, first)) continue;
            int next = first + 3;
            if (score.meterChanges().stream()
                    .noneMatch(
                            m ->
                                    m.measureIndex() == next
                                            && m.numerator() == 3
                                            && m.denominator() == 4)) continue;
            if (closings(score, gray, w, h, first)) {
                beats[first] = 3;
                beats[first + 1] = 3;
            }
        }
    }

    private static boolean inCommonTime(
            List<ScoreMeterChange> changes, ScoreMeterChange initial, int first) {
        var active = initial;
        for (var change : changes) {
            if (change.measureIndex() > first + 1) break;
            if (change.measureIndex() >= first - 1) return false;
            active = change;
        }
        return active.numerator() == 4 && active.denominator() == 4;
    }

    private static boolean closings(
            ScorePageInterpretation score, byte[] gray, int w, int h, int first) {
        var bars = score.measures();
        var before = bars.get(first - 1);
        var a = bars.get(first);
        var b = bars.get(first + 1);
        var quarter = bars.get(first + 2);
        var next = bars.get(first + 3);
        float nominal = (a.bottom() - a.top()) * h / 8.2f;
        if (nominal < 9
                || nominal > 27
                || before.cadenceQuarterBeats() != 2
                || quarter.cadenceQuarterBeats() != 1
                || a.cadenceQuarterBeats() != 0
                || b.cadenceQuarterBeats() != 0
                || !closedRow(before, a, h, nominal)
                || !closedRow(a, b, h, nominal)
                || !closedRow(b, quarter, h, nominal)
                || (next.top() - quarter.bottom()) * h < nominal * 2.2f
                || (a.right() - a.left()) * w < 25 * nominal
                || (a.right() - a.left()) * w > 39 * nominal
                || (b.right() - b.left()) * w < 25 * nominal
                || (b.right() - b.left()) * w > 39 * nominal
                || score.rests().stream()
                        .anyMatch(r -> r.measureIndex() >= first && r.measureIndex() <= first + 1))
            return false;

        float start = (before.right() + a.left()) * w * .5f;
        float middle = (a.right() + b.left()) * w * .5f;
        float shortEdge = (b.right() + quarter.left()) * w * .5f;
        float end = quarter.right() * w;
        float[] rails =
                DottedDyadMeasureSeparator.localRails(
                        gray, w, h, Math.round(middle), a.bottom() * h - nominal * 2.1f, nominal);
        if (rails == null) return false;
        float gap = rails[1], floor = rails[0];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, w, staff, start, false, false) < 0
                || PrintedCadenceDurations.boundaryColumn(gray, w, staff, end, false, false) < 0
                || !ClosedSixteenthPairBeforeHalf.ownedSeparator(gray, w, h, staff, middle)
                || !ClosedSixteenthPairBeforeHalf.ownedSeparator(gray, w, h, staff, shortEdge))
            return false;
        int leftGroups = provedGroups(local(score, first), a, gray, w, h, gap, floor);
        int rightGroups = provedGroups(local(score, first + 1), b, gray, w, h, gap, floor);
        return leftGroups >= 1 && rightGroups >= 1 && leftGroups + rightGroups >= 3;
    }

    private static boolean closedRow(MeasureRegion a, MeasureRegion b, int h, float gap) {
        return Math.abs(a.top() - b.top()) * h <= gap * .48f
                && Math.abs(a.bottom() - b.bottom()) * h <= gap * .48f
                && b.left() > a.right();
    }

    private static List<ScoreNoteEvent> local(ScorePageInterpretation score, int bar) {
        return score.notes().stream()
                .filter(n -> n.measureIndex() == bar)
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                .toList();
    }

    private static int provedGroups(
            List<ScoreNoteEvent> notes,
            MeasureRegion region,
            byte[] gray,
            int w,
            int h,
            float gap,
            float floor) {
        if (notes.size() != 24) return 0;
        var pairs = new ClosedFourDyadBeam.Head[12];
        float span = (region.right() - region.left()) * w;
        int clef = notes.get(0).clefBottomDiatonic();
        for (int i = 0; i < pairs.length; i++) {
            var n = notes.get(i * 2);
            var mate = notes.get(i * 2 + 1);
            if (!sixteenth(n, clef)
                    || !sixteenth(mate, clef)
                    || n.stemDirection() != mate.stemDirection()
                    || Math.abs(n.staffStep() - mate.staffStep()) != 2
                    || (mate.positionInMeasure() - n.positionInMeasure()) * span > gap * .20f)
                return 0;
            float x =
                    region.left() * w
                            + (n.positionInMeasure() + mate.positionInMeasure()) * .5f * span;
            pairs[i] =
                    new ClosedFourDyadBeam.Head(
                            x,
                            Math.min(n.pageY(), mate.pageY()) * h,
                            Math.max(n.pageY(), mate.pageY()) * h);
            if (i > 0 && (x - pairs[i - 1].x() < gap * 1.35f || x - pairs[i - 1].x() > gap * 3.88f))
                return 0;
        }
        int witnessed = 0;
        for (int i = 0; i < pairs.length; i += 4) {
            int dir = notes.get(i * 2).stemDirection();
            boolean common = true;
            for (int j = 1; j < 4; j++)
                if (notes.get((i + j) * 2).stemDirection() != dir) common = false;
            if (common
                    && ClosedFourDyadBeam.provesOctaves(
                            gray,
                            w,
                            h,
                            Arrays.copyOfRange(pairs, i, i + 4),
                            gap,
                            floor,
                            -dir,
                            true)) witnessed++;
        }
        return witnessed;
    }

    private static boolean sixteenth(ScoreNoteEvent n, int clef) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.clefBottomDiatonic() == clef
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.stemDirection() != 0
                && n.boundaryTies() == 0
                && !n.crossStaffBeam()
                && (n.articulations() & NoteOrnament.GRACE) == 0
                && n.beamCount() == 2
                && n.unbeamedDurationBeats() == 0
                && n.tupletDivisor() == 1
                && n.tupletNormalNotes() == 1
                && n.augmentationDots() == 0
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0;
    }
}
