// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Literal sharp ownership within a caller-proved eighth-triplet/sixteenth bar. */
final class MixedTripletSharpPitches {
    private MixedTripletSharpPitches() {}

    record Result(List<ScoreNoteEvent> notes, List<Integer> printedSharpOwners) {}

    static Result restore(List<ScoreNoteEvent> original, List<Integer> owners, MeasureRegion bar,
            byte[] raw, byte[] normalized, int width, int height) {
        var unchanged = new Result(original, List.of());
        if (owners.size() != 14 || raw == null || normalized == null
                || raw.length != (long) width * height || normalized.length != raw.length) return unchanged;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 24) return unchanged;
        int measure = original.get(owners.get(0)).measureIndex();
        for (int i = 0; i < owners.size(); i++) {
            var n = original.get(owners.get(i));
            if (n.measureIndex() != measure || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.kind() != ScoreNoteEvent.Kind.PITCHED || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.beamCount() != (i < 6 ? 1 : 2) || n.augmentationDots() != 0
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.crossStaffBeam()
                    || n.harmonicSemitones() != 0 || n.openStringTouch() != null) return unchanged;
        }
        List<ScoreNoteEvent> assigned = null;
        var printed = new ArrayList<Integer>();
        for (int i = 6; i < owners.size(); i++) {
            int owner = owners.get(i);
            var head = original.get(owner);
            if (head.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) continue;
            var before = original.get(owners.get(i - 1));
            float x = headX(bar, head, width), y = head.pageY() * height;
            float previousX = headX(bar, before, width), distance = x - previousX;
            if (distance < 2.5f * gap || distance > 4.1f * gap) continue;
            var first = AccidentalSlurSpines.prove(raw, width, previousX, x, y, gap, 1);
            if (first.size() != 2 || !first.equals(
                    AccidentalSlurSpines.prove(normalized, width, previousX, x, y, gap, 1))) continue;
            if (assigned == null) assigned = new ArrayList<>(original);
            assigned.set(owner, head.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_SHARP));
            printed.add(owner);
            for (int j = i + 1; j < owners.size(); j++) {
                int later = owners.get(j);
                var n = original.get(later);
                if (n.diatonicPitchIdentity() != head.diatonicPitchIdentity()
                        || n.octaveShift() != head.octaveShift()) continue;
                if (n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) break;
                assigned.set(later, n.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_SHARP));
            }
        }
        return assigned == null ? unchanged : new Result(List.copyOf(assigned), List.copyOf(printed));
    }

    private static float headX(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }
}
