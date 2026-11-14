// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Recognizes a full 6/4 bar written as two three-beat phrases across a system break. */
final class WrappedTripletMeasure {
    private WrappedTripletMeasure() {}

    record Timing(double onset, double duration) {}
    record Proof(ScorePageInterpretation score, List<Integer> continuations, Timing[] written) {}
    record Half(List<Integer> owners, float[] x) {}

    static Proof capture(ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, int width, int height, float[] beats) {
        var written = new Timing[score.notes().size()];
        if (gray == null || gray.length != (long) width * height || beats.length < 4)
            return new Proof(score, List.of(), written);
        var notes = new ArrayList<>(score.notes());
        var byMeasure = new ArrayList<List<Integer>>(beats.length);
        for (int i = 0; i < beats.length; i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byMeasure.get(notes.get(i).measureIndex()).add(i);
        var regions = score.measures();
        var continuations = new ArrayList<Integer>();
        var meter = inherited;
        int nextMeter = 0;
        for (int i = 0; i + 2 < beats.length; i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            if (i == 0 || meter.numerator() != 6 || meter.denominator() != 4
                    || beats[i] != 6 || beats[i+1] != 6
                    || regions.get(i).bottom() >= regions.get(i+1).top()
                    || byMeasure.get(i).size() != 9 || byMeasure.get(i+1).size() != 9)
                continue;
            final int first = i;
            if (score.meterChanges().stream().anyMatch(m -> m.measureIndex() == first+1)
                    || score.keyChanges().stream().anyMatch(k -> k.measureIndex() == first+1)
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == first || r.measureIndex() == first+1)
                    || score.multiRestGroups().stream().anyMatch(g -> g.firstMeasureIndex() <= first+1
                            && g.firstMeasureIndex()+g.measureCount() > first))
                continue;
            var lead = half(gray, width, height, regions.get(i), notes, byMeasure.get(i));
            var tail = half(gray, width, height, regions.get(i+1), notes, byMeasure.get(i+1));
            boolean undotted = false;
            if (lead == null || tail == null) {
                if (beats[i-1] != 6 || beats[i+2] != 6
                        || byMeasure.get(i-1).size() != 18 || byMeasure.get(i+2).size() != 18) continue;
                lead = WrappedEighthTriplets.half(gray, width, height, regions.get(i), notes, byMeasure.get(i));
                tail = WrappedEighthTriplets.half(gray, width, height, regions.get(i+1), notes, byMeasure.get(i+1));
                undotted = true;
            }
            if (lead == null || tail == null || !WrappedMeasureBoundaries.confirms(
                    gray, width, height, regions, i, lead.x()[8], tail.x()[0], undotted)) continue;
            if (notes.get(lead.owners().get(0)).clefBottomDiatonic()
                    != notes.get(tail.owners().get(0)).clefBottomDiatonic())
                continue;
            assign(lead, notes, written, undotted);
            assign(tail, notes, written, undotted);
            beats[i] = 3;
            beats[i+1] = 3;
            continuations.add(i+1);
        }
        if (continuations.isEmpty()) return new Proof(score, List.of(), written);
        return new Proof(score.withNotes(notes, IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource()), List.copyOf(continuations), written);
    }

    static Half half(byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates) {
        if (candidates.size() != 9) return null;
        var owners = candidates.stream()
                .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
        float[] x = new float[9], y = new float[9];
        float left = bar.left() * width, right = bar.right() * width;
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        for (int i = 0; i < 9; i++) {
            var note = notes.get(owners.get(i));
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1 || note.staffIndex() != 0
                    || note.crossStaffBeam() || note.tupletDivisor() != 1 || note.beamCount() != (i % 3 == 1 ? 2 : 1)
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0
                    || Math.abs(note.stemDirection()) != 1
                    || note.clefBottomDiatonic() != clef || clef == ScoreNoteEvent.CLEF_UNKNOWN)
                return null;
            x[i] = left + note.positionInMeasure() * (right - left);
            y[i] = note.pageY() * height;
        }
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap) || gap < 10 || gap > 30) return null;
        var samples = new ArrayList<float[]>();
        for (int j = 0; j <= 12; j++) {
            float at = left + (right - left) * j / 12;
            var rails = PrintedDirectionStaff.printedRules(gray, width, height, at, bar.bottom() * height - 2.1f * gap, gap);
            if (rails != null) samples.add(new float[]{at, rails[0], rails[1]});
        }
        if (samples.size() < 3) return null;
        var track = StaffPitchTrack.fromVerifiedSamples(samples);
        for (int i = 0; i < 9; i++) {
            float localGap = track.at(x[i])[1];
            float next = i < 8 ? x[i+1] : Math.min(right, x[i] + 3 * localGap);
            if (i < 8 && (next - x[i] < localGap * 1.4f || next - x[i] > localGap * 4.0f))
                return null;
            if (next - x[i] >= localGap * 1.45f
                    && (WrappedTripletDotInk.uniqueCore(gray, width, height, x[i], y[i], next, localGap) != null)
                    != (i % 3 == 0)) return null;
            if (i % 3 == 0 && next - x[i] < localGap * 1.45f) return null;
            if (i == 8) continue;
            int stem = notes.get(owners.get(i)).stemDirection();
            boolean same = stem == notes.get(owners.get(i+1)).stemDirection();
            if (i % 3 < 2) {
                if (!same || !PrintedTupletBeamOwner.connectedHeads(
                        gray, width, height, x[i], y[i], x[i+1], y[i+1], localGap, stem)) return null;
            } else if (same && PrintedTupletBeamOwner.connectedAwayFromRules(
                    gray, width, height, x[i], y[i], x[i+1], y[i+1], localGap, stem, track)) return null;
        }
        return new Half(owners, x);
    }

    private static void assign(Half half, List<ScoreNoteEvent> notes, Timing[] written, boolean undotted) {
        double[] onset = undotted ? new double[] {0, 1.0/3, 2.0/3} : new double[] {0, .5, 2.0/3};
        double[] duration = undotted
                ? new double[] {1.0/3, 1.0/3, 1.0/3} : new double[] {.5, 1.0/6, 1.0/3};
        for (int i = 0; i < 9; i++) {
            int index = half.owners().get(i);
            var n = notes.get(index);
            var replacement = new ScoreNoteEvent(
                    n.measureIndex(), n.positionInMeasure(), n.staffStep(), n.staffIndex(), n.staffCount(),
                    n.pageY(), n.tiedFromPrevious(), !undotted && i % 3 == 0 ? 1 : 0,
                    undotted ? 1 : n.beamCount(), n.writtenAccidental(),
                    n.unbeamedDurationBeats(), 3, n.followingRestBeats(), n.articulations(),
                    n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(), n.compactOpening(),
                    n.octaveShift(), n.boundaryTies(), 2, n.stemDirection(), n.kind(), n.visualSize(),
                    n.harmonicSemitones(), n.openStringTouch());
            notes.set(index, replacement);
            written[index] = new Timing(i/3 + onset[i%3], duration[i%3]);
        }
    }
}
