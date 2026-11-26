// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Shortened triplet marking in a repeated four-bar passage of independently beamed triplets. */
final class RepeatedBeamedTriplets {
    private static final int ATTACKS = 12;

    private RepeatedBeamedTriplets() {}

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height,
            ScoreMeterChange inherited) {
        var notes = score.notes();
        var bars = score.measures();
        if (bars.size() < 4 || notes.size() < ATTACKS * 4 || width < 1 || height < 1
                || gray == null || gray.length != (long) width * height) return score;
        var ordered = new ArrayList<List<Integer>>(bars.size());
        for (int m = 0; m < bars.size(); m++) ordered.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) ordered.get(notes.get(i).measureIndex()).add(i);
        var sortedByX = Comparator.comparingDouble((Integer i) -> notes.get(i).positionInMeasure());
        var withRest = new BitSet();
        for (var rest : score.rests()) withRest.set(rest.measureIndex());
        int[] marks = new int[bars.size()];
        Arrays.fill(marks, -1);
        int nextMeter = 0;
        var meter = inherited;
        for (int m = 0; m < bars.size(); m++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= m)
                meter = score.meterChanges().get(nextMeter++);
            var indices = ordered.get(m);
            if (indices.size() != ATTACKS || withRest.get(m) || bars.get(m).cadenceQuarterBeats() != 0
                    || meter != null && meter.numerator() != meter.denominator()) continue;
            indices.sort(sortedByX);
            marks[m] = printedGroups(notes, indices);
        }

        List<ScoreNoteEvent> result = notes;
        for (int first = 0; first + 3 < bars.size(); first++) {
            int reprise = first + 2, last = first + 3;
            if (marks[first] < 0 || marks[first + 1] < 0 || marks[reprise] < 0 || marks[last] < 0
                    || (marks[first] & marks[reprise]) == 0
                    || Integer.bitCount(marks[first] | marks[reprise]) < 3
                    || !sameWrittenPitches(notes, ordered.get(first), ordered.get(reprise))
                    || !sameWrittenPitches(notes, ordered.get(first + 1), ordered.get(last))
                    || sameWrittenPitches(notes, ordered.get(first), ordered.get(first + 1))) continue;
            int start = first;
            if (score.meterChanges().stream().anyMatch(m -> m.measureIndex() > start && m.measureIndex() <= last)
                    || score.keyChanges().stream().anyMatch(k -> k.measureIndex() > start && k.measureIndex() <= last))
                continue;
            boolean beams = true;
            for (int m = first; m <= last && beams; m++)
                beams = independentBeams(notes, ordered.get(m), bars, m, labels, gray, width, height);
            if (!beams) continue;
            if (result == notes) result = new ArrayList<>(notes);
            for (int m = first; m <= last; m++)
                for (int i : ordered.get(m)) result.set(i, notes.get(i).withTupletRatio(3, 2));
            first = last;
        }
        return result == notes ? score
                : score.withNotes(result, IntStream.range(0, notes.size()).boxed().toList())
                        .withClockContextSource(score.clockContextSource());
    }

    private static int printedGroups(List<ScoreNoteEvent> notes, List<Integer> indices) {
        int marks = 0;
        float min = Float.MAX_VALUE, max = 0;
        for (int i = 0; i < ATTACKS; i++) {
            var n = notes.get(indices.get(i));
            if (n.staffIndex() != 0 || n.staffCount() != 1 || n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.beamCount() != 1 || n.augmentationDots() != 0 || n.crossStaffBeam()
                    || n.unbeamedDurationBeats() != 0 || n.tiedFromPrevious() || n.boundaryTies() != 0
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_UNKNOWN
                    || (n.articulations() & NoteOrnament.GRACE) != 0 || Math.abs(n.stemDirection()) != 1
                    || !(n.tupletDivisor() == 1 || n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2))
                return -1;
            if (i > 0) {
                var previous = notes.get(indices.get(i - 1));
                float step = n.positionInMeasure() - previous.positionInMeasure();
                if (step <= 0 || n.clefBottomDiatonic() != previous.clefBottomDiatonic()
                        || i % 3 != 0 && (n.tupletDivisor() != previous.tupletDivisor()
                                || n.stemDirection() != previous.stemDirection())) return -1;
                min = Math.min(min, step);
                max = Math.max(max, step);
            }
            if (i % 3 == 0 && n.tupletDivisor() == 3) marks |= 1 << (i / 3);
        }
        return max <= min * 1.22f ? marks : -1;
    }

    private static boolean sameWrittenPitches(List<ScoreNoteEvent> notes, List<Integer> a, List<Integer> b) {
        for (int i = 0; i < ATTACKS; i++) {
            var x = notes.get(a.get(i));
            var y = notes.get(b.get(i));
            if (x.clefBottomDiatonic() != y.clefBottomDiatonic() || x.staffStep() != y.staffStep()
                    || x.writtenAccidental() != y.writtenAccidental() || x.octaveShift() != y.octaveShift())
                return false;
        }
        return true;
    }

    private static boolean independentBeams(
            List<ScoreNoteEvent> notes, List<Integer> owners, List<MeasureRegion> bars, int at,
            byte[] labels, byte[] gray, int width, int height) {
        var bar = bars.get(at);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 30) return false;
        var budget = new PrintedBeamShafts.Budget(800_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(ATTACKS);
        float[] xs = new float[ATTACKS], ys = new float[ATTACKS];
        for (int i = 0; i < ATTACKS; i++) {
            var n = notes.get(owners.get(i));
            xs[i] = (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * width;
            ys[i] = n.pageY() * height;
            var source = PrintedBeamShafts.at(gray, width, height, xs[i], ys[i], gap, budget);
            if (source.size() != 1 || source.get(0).direction() != -n.stemDirection()) return false;
            shafts.add(source.get(0));
        }
        if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(gray, width, height, bar, xs[0], xs[11])
                && !(WrappedMeasureBoundaries.confirmsShaftBoundedInitialStaffBar(
                        gray, width, height, bars, at, xs[0], shafts.get(0).left())
                        && WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                                gray, width, height, bar, xs[11]))) return false;
        for (int i = 0; i < ATTACKS - 1; i++) {
            var dot = WrappedTripletDotInk.uniqueCore(gray, width, height, xs[i], ys[i], xs[i + 1], gap);
            if (dot != null && (notes.get(owners.get(i + 1)).writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                    || !WrappedEighthTriplets.naturalOwnsDot(
                            gray, labels, width, height, dot, xs[i + 1], ys[i + 1], gap))) return false;
            var a = shafts.get(i);
            var b = shafts.get(i + 1);
            if (i % 3 == 2) {
                if (a.direction() == b.direction()
                        && !WrappedEighthTriplets.cutBetweenRecoveredGroups(
                                gray, width, height, bar, a, b, gap)) return false;
            } else if (a.direction() != b.direction()
                    || !WrappedEighthTriplets.matchesBeamRails(gray, width, height, a, b, gap, 1))
                return false;
        }
        return true;
    }
}
