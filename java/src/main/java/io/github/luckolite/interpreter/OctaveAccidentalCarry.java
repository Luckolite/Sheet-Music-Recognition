// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The actual printed owner of an accidental propagated within a measure. */
record OctaveAccidentalCarry(ScoreNoteEvent owner, ScoreNoteEvent carried) {
    private record NoteLocation(
            int measure, int staff, int staffCount, float position, float pageY, int writtenPitch) {
        NoteLocation(ScoreNoteEvent note) {
            this(note.measureIndex(), note.staffIndex(), note.staffCount(), note.positionInMeasure(),
                    note.pageY(), note.diatonicPitchIdentity());
        }
    }

    static void clearAtRangeBoundary(List<ScoreNoteEvent> before, List<ScoreNoteEvent> assigned,
            boolean[] printedRange, List<OctaveAccidentalCarry> carries) {
        var positions = uniquePositions(before);
        var clear = new BitSet(before.size());
        var inherited = new BitSet(before.size());
        for (var carry : carries) {
            int from = positions.getOrDefault(new NoteLocation(carry.owner), -1);
            int to = positions.getOrDefault(new NoteLocation(carry.carried), -1);
            if (from < 0 || to <= from) continue;
            inherited.set(to);
            if (printedRange[from] == printedRange[to]) continue;
            var owner = assigned.get(from);
            var note = assigned.get(to);
            if (owner.octaveShift() == note.octaveShift()
                    || owner.writtenAccidental() != note.writtenAccidental()
                    || note.writtenAccidental() != carry.carried.writtenAccidental()
                    || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || note.harmonicSemitones() != 0 || note.openStringTouch() != null
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL) continue;
            clear.set(to);
        }
        // The repeated written octave retains two independent accidental carries of the same
        // alteration. Their origins need not share an attack if a later sign renewed only one.
        for (int i = clear.nextSetBit(0); i >= 0; i = clear.nextSetBit(i + 1)) {
            if (hasOctaveCarry(before, clear, i, i - 1) || hasOctaveCarry(before, clear, i, i + 1))
                continue;
            var note = assigned.get(i);
            assigned.set(i, note.withWrittenAccidental(priorLocalAccidental(assigned, inherited, i)));
        }
    }

    /** A sign applied before the octave passage still governs its pitch later in the same bar. */
    private static int priorLocalAccidental(List<ScoreNoteEvent> assigned, BitSet inherited, int index) {
        var note = assigned.get(index);
        int pitch = note.diatonicPitchIdentity();
        for (int i = index - 1; i >= 0; i--) {
            var prior = assigned.get(i);
            if (prior.measureIndex() != note.measureIndex()) break;
            boolean sameWrittenPitch = prior.diatonicPitchIdentity() == pitch
                    && prior.octaveShift() == note.octaveShift();
            // An independent sign can already govern the sounding octave at entry.
            boolean sameSoundingPitchAtEntry = note.octaveShift() != 0 && prior.octaveShift() == 0
                    && prior.diatonicPitchIdentity() == pitch + 7 * note.octaveShift()
                    && prior.writtenAccidental() == note.writtenAccidental();
            if (inherited.get(i) || prior.kind() != ScoreNoteEvent.Kind.PITCHED
                    || prior.staffIndex() != note.staffIndex() || prior.staffCount() != note.staffCount()
                    || (!sameWrittenPitch && !sameSoundingPitchAtEntry)
                    || prior.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || prior.tiedFromPrevious() || prior.boundaryTies() != 0
                    || prior.harmonicSemitones() != 0 || prior.openStringTouch() != null
                    || prior.visualSize() != ScoreNoteEvent.VisualSize.FULL) continue;
            return prior.writtenAccidental();
        }
        return ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
    }

    static void restoreStoppedPitches(List<ScoreNoteEvent> before, List<OctaveAccidentalCarry> carries,
            Set<ScoreNoteEvent> touches, Map<ScoreNoteEvent, ScoreNoteEvent> stops) {
        if (touches.isEmpty() || carries.isEmpty()) return;
        var positions = uniquePositions(before);
        var inherited = new BitSet(before.size());
        var fromTouch = new BitSet(before.size());
        for (var carry : carries) {
            int from = positions.getOrDefault(new NoteLocation(carry.owner), -1);
            int to = positions.getOrDefault(new NoteLocation(carry.carried), -1);
            if (from < 0 || to <= from) continue;
            inherited.set(to);
            var note = before.get(to);
            if (touches.contains(before.get(from)) && stops.containsKey(note)
                    && note.writtenAccidental() == carry.carried.writtenAccidental()
                    && before.get(from).writtenAccidental() == note.writtenAccidental()
                    && !note.tiedFromPrevious() && note.boundaryTies() == 0) fromTouch.set(to);
        }
        for (int i = fromTouch.nextSetBit(0); i >= 0; i = fromTouch.nextSetBit(i + 1)) {
            var note = before.get(i);
            int accidental = ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
            // The touch sign stays with its diamond. A previous printed stopped-note sign
            // still governs the stopped pitch after removing the touch from that state.
            for (int j = i - 1; j >= 0; j--) {
                var prior = before.get(j);
                if (prior.measureIndex() != note.measureIndex()) break;
                if (inherited.get(j) || touches.contains(prior)
                        || prior.kind() != ScoreNoteEvent.Kind.PITCHED
                        || prior.staffIndex() != note.staffIndex() || prior.staffCount() != note.staffCount()
                        || prior.diatonicPitchIdentity() != note.diatonicPitchIdentity()
                        || prior.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        || prior.tiedFromPrevious() || prior.boundaryTies() != 0) continue;
                accidental = prior.writtenAccidental();
                break;
            }
            stops.put(note, stops.get(note).withWrittenAccidental(accidental));
        }
    }

    private static Map<NoteLocation, Integer> uniquePositions(List<ScoreNoteEvent> before) {
        var positions = new HashMap<NoteLocation, Integer>();
        for (int i = 0; i < before.size(); i++) {
            var note = before.get(i);
            if (note.kind() == ScoreNoteEvent.Kind.PITCHED)
                positions.merge(new NoteLocation(note), i, (a, b) -> -1);
        }
        return positions;
    }

    private static boolean hasOctaveCarry(
            List<ScoreNoteEvent> notes, BitSet clear, int index, int otherIndex) {
        if (otherIndex < 0 || !clear.get(otherIndex)) return false;
        var note = notes.get(index);
        var other = notes.get(otherIndex);
        return note.measureIndex() == other.measureIndex()
                && note.staffIndex() == other.staffIndex()
                && note.staffCount() == other.staffCount()
                && note.clefBottomDiatonic() == other.clefBottomDiatonic()
                && Math.abs(note.staffStep() - other.staffStep()) == 7
                && note.writtenAccidental() == other.writtenAccidental()
                && note.stemDirection() != 0
                && note.stemDirection() == other.stemDirection()
                && note.beamCount() == other.beamCount()
                && note.tupletDivisor() == other.tupletDivisor()
                && Math.abs(note.positionInMeasure() - other.positionInMeasure()) <= .002f;
    }
}
