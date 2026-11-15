// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** A divided two-beat run before a separately barred terminal half dyad. */
final class ClosedBeamedHalfBar {
    private ClosedBeamedHalfBar() {}

    static void apply(
            ScorePageInterpretation score,
            ScoreMeterChange initialMeter,
            byte[] gray,
            int width,
            int height,
            float[] beats) {
        var measures = score.measures();
        if (gray == null
                || gray.length != (long) width * height
                || measures.size() != beats.length
                || measures.size() < 4) return;
        ScoreMeterChange active = initialMeter;
        int change = 0;
        var meters = score.meterChanges();
        for (int i = 0; i + 2 < beats.length; i++) {
            while (change < meters.size() && meters.get(change).measureIndex() <= i)
                active = meters.get(change++);
            if (i == 0
                    || active.numerator() != 4
                    || active.denominator() != 4
                    || beats[i - 1] != 4
                    || beats[i] != 4
                    || beats[i + 1] != 2
                    || measures.get(i + 1).cadenceQuarterBeats() != 2
                    || change < meters.size() && meters.get(change).measureIndex() == i + 1
                    || change > 0 && active.measureIndex() >= i - 1) continue;
            int bar = i;
            if (score.rests().stream()
                    .anyMatch(r -> r.measureIndex() >= bar - 1 && r.measureIndex() <= bar + 1))
                continue;
            var prior = notesIn(score, i - 1);
            var run = notesIn(score, i);
            var half = notesIn(score, i + 1);
            if (prior.size() != 32 || run.size() != 16 || half.size() != 2) continue;
            int clef = run.get(0).clefBottomDiatonic();
            if (!prior.stream().allMatch(n -> sixteenth(n, clef) && n.stemDirection() == -1)
                    || !run.stream().allMatch(n -> sixteenth(n, clef))
                    || !half.stream()
                            .allMatch(
                                    n ->
                                            ordinary(n, clef)
                                                    && n.beamCount() == 0
                                                    && n.unbeamedDurationBeats() == 2
                                                    && n.stemDirection() != 0)) continue;
            if (closed(measures, i, run, gray, width, height)) beats[i] = 2;
        }
    }

    private static List<ScoreNoteEvent> notesIn(ScorePageInterpretation score, int bar) {
        return score.notes().stream().filter(n -> n.measureIndex() == bar).toList();
    }

    private static boolean sixteenth(ScoreNoteEvent n, int clef) {
        return ordinary(n, clef)
                && n.beamCount() == 2
                && n.unbeamedDurationBeats() == 0
                && n.stemDirection() != 0;
    }

    private static boolean ordinary(ScoreNoteEvent n, int clef) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.clefBottomDiatonic() == clef
                && n.augmentationDots() == 0
                && n.tupletDivisor() == 1
                && n.tupletNormalNotes() == 1
                && !n.crossStaffBeam()
                && !n.tiedFromPrevious()
                && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean closed(
            List<MeasureRegion> measures,
            int index,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height) {
        var prior = measures.get(index - 1);
        var bar = measures.get(index);
        var half = measures.get(index + 1);
        var nextSystem = measures.get(index + 2);
        float estimate = (bar.bottom() - bar.top()) * height / 8.2f;
        float span = (bar.right() - bar.left()) * width;
        if (estimate < 10
                || estimate > 27
                || span < 16 * estimate
                || span > 24 * estimate
                || Math.abs(prior.top() - bar.top()) * height > estimate * .35f
                || Math.abs(half.top() - bar.top()) * height > estimate * .35f
                || (nextSystem.top() - half.bottom()) * height < estimate * 2
                || (bar.left() - prior.right()) * width < 0
                || (bar.left() - prior.right()) * width > estimate * 1.5f
                || (half.left() - bar.right()) * width < 0
                || (half.left() - bar.right()) * width > estimate * 1.5f) return false;
        float left = (prior.right() + bar.left()) * .5f, right = (bar.right() + half.left()) * .5f;
        float floor = bar.bottom() * height - 2.1f * estimate;
        var row = new ArrayList<>(notes);
        row.sort(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure));
        int leftDirection = row.get(0).stemDirection();
        int rightDirection = row.get(8).stemDirection();
        boolean mixed = leftDirection != rightDirection;
        float leftSample = left * width - .35f * estimate;
        float rightSample = right * width - (mixed ? 2.1f : .9f) * estimate;
        var leftStaff =
                PrintedDirectionStaff.printedRules(
                        gray, width, height, leftSample, floor, estimate);
        var rightStaff =
                PrintedDirectionStaff.printedRules(
                        gray, width, height, rightSample, floor, estimate);
        if (leftStaff == null
                || rightStaff == null
                || Math.abs(leftStaff[0] - rightStaff[0]) > .15f * estimate
                || Math.abs(leftStaff[1] - rightStaff[1]) > .10f * estimate) return false;
        float gap = (leftStaff[1] + rightStaff[1]) * .5f;
        float staffBottom = (leftStaff[0] + rightStaff[0]) * .5f;
        var heads = new ClosedFourDyadBeam.Head[8];
        for (int i = 0; i < heads.length; i++) {
            var a = row.get(2 * i);
            var b = row.get(2 * i + 1);
            int direction = i < 4 ? leftDirection : rightDirection;
            if (Math.abs(a.staffStep() - b.staffStep()) != 7
                    || (b.positionInMeasure() - a.positionInMeasure()) * span > gap * .16f
                    || a.stemDirection() != direction
                    || b.stemDirection() != direction) return false;
            float center =
                    bar.left() * width
                            + (a.positionInMeasure() + b.positionInMeasure()) * .5f * span;
            heads[i] =
                    new ClosedFourDyadBeam.Head(
                            center,
                            Math.min(a.pageY(), b.pageY()) * height,
                            Math.max(a.pageY(), b.pageY()) * height);
        }
        var bounded = new MeasureRegion(left, right, bar.top(), bar.bottom());
        boolean edges =
                mixed
                        ? WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                                gray,
                                width,
                                height,
                                bounded,
                                heads[0].x(),
                                heads[7].x(),
                                StaffPitchTrack.fromVerifiedSamples(
                                        List.of(
                                                new float[] {
                                                    leftSample, leftStaff[0], leftStaff[1]
                                                },
                                                new float[] {
                                                    rightSample, rightStaff[0], rightStaff[1]
                                                })))
                        : WrappedMeasureBoundaries.confirmsClosed(
                                gray, width, height, bounded, heads[0].x(), heads[7].x());
        return edges
                && ClosedFourDyadBeam.provesOctaves(
                        gray,
                        width,
                        height,
                        Arrays.copyOfRange(heads, 0, 4),
                        gap,
                        staffBottom,
                        -leftDirection,
                        mixed)
                && ClosedFourDyadBeam.provesOctaves(
                        gray,
                        width,
                        height,
                        Arrays.copyOfRange(heads, 4, 8),
                        gap,
                        staffBottom,
                        -rightDirection,
                        mixed);
    }
}
