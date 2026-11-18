// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/** Carries two optically complete 2-beat sixteenth-dyad halves as one printed common-time bar. */
final class WrappedSixteenthMeasure {
    private WrappedSixteenthMeasure() {}
    private record Attack(int owner, int position) {}

    static WrappedTripletMeasure.Proof capture(
            WrappedTripletMeasure.Proof previous, ScoreMeterChange inherited,
            byte[] gray, int width, int height, float[] beats, byte[] labels, int[] keys) {
        if (gray == null || labels == null || gray.length != (long) width * height
                || labels.length != gray.length || beats.length < 4) return previous;
        var score = previous.score();
        var bars = score.measures();
        List<PlayingTechniqueDetector.Staff> staffs = null;
        List<ScoreNoteEvent> notes = null;
        var continuations = new ArrayList<>(previous.continuations());
        var meter = inherited;
        int nextMeter = 0;
        for (int i = 0; i + 2 < beats.length; i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            if (i == 0 || beats[i] != 4 || beats[i+1] != 4
                    || meter.numerator() != 4 || meter.denominator() != 4
                    || bars.get(i).bottom() >= bars.get(i+1).top()
                    || bars.get(i-1).bottom() < bars.get(i).top()
                    || bars.get(i+1).bottom() < bars.get(i+2).top()) continue;
            int first = i, second = i + 1;
            if (score.meterChanges().stream().anyMatch(m -> m.measureIndex() == second)
                    || score.keyChanges().stream().anyMatch(k -> k.measureIndex() == second)
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == first || r.measureIndex() == second)
                    || score.multiRestGroups().stream().anyMatch(
                            g -> g.firstMeasureIndex() <= second
                                    && g.firstMeasureIndex() + g.measureCount() > first)) continue;
            if (staffs == null)
                staffs = OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, bars);
            var out = staff(staffs, bars.get(first), height);
            var in = staff(staffs, bars.get(second), height);
            if (out == null || in == null || Math.abs(out.gap() - in.gap()) > out.gap() * .15f) continue;
            var outgoing = WrappedSixteenthSource.half(labels, gray, width, height, bars.get(first), out.gap());
            var incoming = WrappedSixteenthSource.half(labels, gray, width, height, bars.get(second), in.gap());
            if (outgoing.isEmpty() || incoming.isEmpty()) continue;
            if (!WrappedMeasureBoundaries.confirmsSixteenthDyads(
                    gray, width, height, bars, first, outgoing.get(7).x(), incoming.get(0).x(),
                    labels, keys[first], out, in)) continue;
            var lead = attacks(score.notes(), bars.get(first), first, outgoing, width, height, out.gap());
            var tail = attacks(score.notes(), bars.get(second), second, incoming, width, height, in.gap());
            if (lead.isEmpty() || tail.isEmpty() || lead.size() + tail.size() < 16) continue;
            if (notes == null) notes = new ArrayList<>(score.notes());
            assign(lead, notes, previous.written());
            assign(tail, notes, previous.written());
            beats[first] = 2;
            beats[second] = 2;
            continuations.add(second);
        }
        if (notes == null) return previous;
        continuations.sort(Integer::compareTo);
        return new WrappedTripletMeasure.Proof(
                score.withNotes(notes, IntStream.range(0, notes.size()).boxed().toList())
                        .withClockContextSource(score.clockContextSource()),
                List.copyOf(continuations), previous.written());
    }

    private static PlayingTechniqueDetector.Staff staff(
            List<PlayingTechniqueDetector.Staff> staffs, MeasureRegion r, int height) {
        PlayingTechniqueDetector.Staff match = null;
        for (var staff : staffs) {
            if (staff.index() != 0 || staff.count() != 1
                    || staff.top() < r.top() * height || staff.bottom() > r.bottom() * height)
                continue;
            if (match != null) return null;
            match = staff;
        }
        return match;
    }

    private static List<Attack> attacks(List<ScoreNoteEvent> notes, MeasureRegion region,
            int bar, List<WrappedSixteenthSource.Slot> slots, int width, int height, float gap) {
        var assignments = new ArrayList<Attack>();
        boolean[][] seen = new boolean[8][2];
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.measureIndex() != bar) continue;
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.augmentationDots() != 0 || n.tupletDivisor() != 1
                    || n.beamCount() < 1 || n.beamCount() > 2 || n.crossStaffBeam()
                    || n.followingRestBeats() != 0 || n.leadingRestBeats() != 0
                    || n.tiedFromPrevious() || n.boundaryTies() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0) return List.of();
            float x = (region.left() + n.positionInMeasure() * (region.right() - region.left())) * width;
            float y = n.pageY() * height;
            boolean matched = false;
            for (int k = 0; k < slots.size(); k++) {
                var slot = slots.get(k);
                if (Math.abs(x - slot.x()) > gap * .30f) continue;
                int side = Math.abs(y - slot.upper()) < Math.abs(y - slot.lower()) ? 0 : 1;
                float headY = side == 0 ? slot.upper() : slot.lower();
                if (seen[k][side] || Math.abs(y - headY) > gap * .30f) return List.of();
                seen[k][side] = true;
                assignments.add(new Attack(i,k));
                matched = true;
                break;
            }
            if (!matched) return List.of();
        }
        return Arrays.stream(seen).filter(s -> s[0] && s[1]).count() >= 2 ? assignments : List.of();
    }

    /** The printed count can place existing attacks even when a model omitted other heads. */
    private static void assign(List<Attack> attacks, List<ScoreNoteEvent> notes,
            WrappedTripletMeasure.Timing[] written) {
        for (var attack : attacks) {
            var n = notes.get(attack.owner);
            if (n.beamCount() != 2) {
                notes.set(attack.owner, new ScoreNoteEvent(
                        n.measureIndex(), n.positionInMeasure(), n.staffStep(), n.staffIndex(), n.staffCount(),
                        n.pageY(), n.tiedFromPrevious(), n.augmentationDots(), 2, n.writtenAccidental(),
                        n.unbeamedDurationBeats(), n.tupletDivisor(), n.followingRestBeats(), n.articulations(),
                        n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(), n.compactOpening(),
                        n.octaveShift(), n.boundaryTies(), n.tupletNormalNotes(), n.stemDirection(), n.kind(),
                        n.visualSize(), n.harmonicSemitones(), n.openStringTouch()));
            }
            written[attack.owner] = new WrappedTripletMeasure.Timing(attack.position * .25, .25);
        }
    }
}
