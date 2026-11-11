// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

/** Resolves independently printed shoulders only at actual adjacent-page boundaries. */
final class ScoreBoundaryTies {
    private ScoreBoundaryTies() {}

    private static final class SharpOrder {
        private static final int[] VALUES = {3, 0, 4, 1, 5, 2, 6};
    }

    private static final class FlatOrder {
        private static final int[] VALUES = {6, 2, 5, 1, 4, 0, 3};
    }

    private record Match(int current, int earlier, int accidental) {}

    static List<ScoreNoteEvent> resolve(
            List<ScoreNoteEvent> notes, List<ScoreKeyChange> keys, List<Integer> boundaries) {
        var byMeasure = new HashMap<Integer, List<Integer>>();
        for (int index = 0; index < notes.size(); index++) {
            var note = notes.get(index);
            if (note.kind() == ScoreNoteEvent.Kind.UNPITCHED
                    && (note.tiedFromPrevious() || note.boundaryTies() != 0))
                throw new IllegalArgumentException(
                        "Unpitched source tie flags do not prove shared instrument ownership");
            byMeasure.computeIfAbsent(note.measureIndex(), ignored -> new ArrayList<>()).add(index);
        }
        var result = new ArrayList<>(notes);
        // Resolve in score order so a note can carry an accidental through several page breaks.
        for (int boundary : new TreeSet<>(boundaries)) {
            var outgoing = byMeasure.getOrDefault(boundary - 1, List.of());
            var incoming = byMeasure.getOrDefault(boundary, List.of());
            var claims = new HashMap<Integer, Integer>();
            var unique = new ArrayList<Match>();
            for (int currentIndex : incoming) {
                var current = notes.get(currentIndex);
                if (current.kind() != ScoreNoteEvent.Kind.PITCHED
                        || (current.boundaryTies() & 3) == 0
                        || current.leadingRestBeats() > 0
                        || current.clefBottomDiatonic() == ScoreNoteEvent.CLEF_UNKNOWN
                        || incoming.stream().map(notes::get).anyMatch(
                                note -> sameStaff(note, current)
                                        && note.positionInMeasure() < current.positionInMeasure() - .018f))
                    continue;
                Match candidate = null;
                int candidateCount = 0;
                for (int earlierIndex : outgoing) {
                    var earlier = result.get(earlierIndex);
                    if (!compatible(earlier, current)
                            || outgoing.stream().map(notes::get).anyMatch(
                                    note -> sameStaff(note, earlier)
                                            && note.positionInMeasure() > earlier.positionInMeasure() + .018f))
                        continue;
                    int accidental = accidental(earlier, keys);
                    if (accidental == Integer.MIN_VALUE
                            || (current.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                                    && ScoreNoteEvent.accidentalSemitones(current.writtenAccidental())
                                            != ScoreNoteEvent.accidentalSemitones(accidental)))
                        continue;
                    candidate = new Match(currentIndex, earlierIndex, accidental);
                    candidateCount++;
                    claims.merge(earlierIndex, 1, Integer::sum);
                }
                if (candidateCount == 1) unique.add(candidate);
            }
            for (var match : unique)
                if (claims.get(match.earlier()) == 1)
                    result.set(match.current(), tied(notes.get(match.current()), match.accidental()));
        }
        return List.copyOf(result);
    }

    private static boolean sameStaff(ScoreNoteEvent a, ScoreNoteEvent b) {
        return a.staffIndex() == b.staffIndex() && a.staffCount() == b.staffCount();
    }

    private static boolean compatible(ScoreNoteEvent earlier, ScoreNoteEvent current) {
        return earlier.kind() == ScoreNoteEvent.Kind.PITCHED
                && sameStaff(earlier, current)
                && ((earlier.boundaryTies() >> 2) & current.boundaryTies() & 3) != 0
                && TabTieIdentity.compatible(earlier.boundaryTies(), current.boundaryTies())
                && earlier.followingRestBeats() <= 0
                && earlier.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN
                && earlier.diatonicPitchIdentity() == current.diatonicPitchIdentity()
                && earlier.octaveShift() == current.octaveShift();
    }

    private static ScoreNoteEvent tied(ScoreNoteEvent current, int accidental) {
        return new ScoreNoteEvent(
                        current.measureIndex(),
                        current.positionInMeasure(),
                        current.staffStep(),
                        current.staffIndex(),
                        current.staffCount(),
                        current.pageY(),
                        true,
                        current.augmentationDots(),
                        current.beamCount(),
                        accidental,
                        current.unbeamedDurationBeats(),
                        current.tupletDivisor(),
                        current.followingRestBeats(),
                        current.articulations(),
                        current.clefBottomDiatonic(),
                        current.crossStaffBeam(),
                        current.leadingRestBeats(),
                        current.compactOpening(),
                        current.octaveShift(),
                        current.boundaryTies(),
                        current.tupletNormalNotes())
                .withStemDirection(current.stemDirection())
                .withKind(current.kind())
                .withVisualSize(current.visualSize());
    }

    private static int accidental(ScoreNoteEvent note, List<ScoreKeyChange> keys) {
        if (note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
            return note.writtenAccidental();
        Integer fifths = null;
        for (var key : keys) if (key.measureIndex() <= note.measureIndex()) fifths = key.fifths();
        if (fifths == null) return Integer.MIN_VALUE;
        int letter = Math.floorMod(note.diatonicPitchIdentity(), 7);
        int[] order = fifths >= 0 ? SharpOrder.VALUES : FlatOrder.VALUES;
        for (int k = 0; k < Math.min(7, Math.abs(fifths)); k++)
            if (order[k] == letter) return fifths > 0 ? 1 : -1;
        return ScoreNoteEvent.ACCIDENTAL_NATURAL;
    }
}
