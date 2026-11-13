// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Attack-local bow directions on the existing staff-owned technique wire. */
final class BowDirectionDetector {
    private record StaffLane(int index, int count) {}

    private BowDirectionDetector() {}

    static List<ScoreTechniqueChange> detect(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes) {
        if (staffs == null || staffs.isEmpty() || measures == null
                || notes == null || notes.isEmpty() || notes.size() > 4096)
            return List.of();
        var staffPositions = new HashMap<StaffLane, List<Integer>>();
        for (int i = 0; i < staffs.size(); i++) {
            var staff = staffs.get(i);
            var lane = new StaffLane(staff.index(), staff.count());
            staffPositions.computeIfAbsent(lane, ignored -> new ArrayList<>()).add(i);
        }
        List<NoteArticulationDetector.Anchor> anchors = new ArrayList<>(notes.size());
        for (var note : notes) {
            if (note.measureIndex() < 0 || note.measureIndex() >= measures.size()) return List.of();
            var m = measures.get(note.measureIndex());
            float x = (m.left() + (m.right() - m.left()) * note.positionInMeasure()) * width;
            float y = note.pageY() * height, best = Float.POSITIVE_INFINITY;
            int owner = -1;
            boolean ambiguous = false;
            var lane = new StaffLane(note.staffIndex(), note.staffCount());
            for (int i : staffPositions.getOrDefault(lane, List.of())) {
                var staff = staffs.get(i);
                float distance = Math.abs(y - (staff.top() + staff.bottom()) * .5f);
                if (distance < best) {
                    best = distance;
                    owner = i;
                    ambiguous = false;
                } else if (distance == best) ambiguous = true;
            }
            float gap = owner < 0 ? 0 : staffs.get(owner).gap();
            anchors.add(new NoteArticulationDetector.Anchor(x, y, gap, ambiguous ? -1 : owner));
        }
        List<ScoreTechniqueChange> result = new ArrayList<>();
        for (var mark :
                NoteArticulationDetector.bowMarks(
                        labels, gray, width, height, anchors, notes, staffs)) {
            var n = notes.get(mark.owner());
            result.add(
                    new ScoreTechniqueChange(
                            n.measureIndex(),
                            n.positionInMeasure(),
                            n.staffIndex(),
                            n.staffCount(),
                            mark.technique()));
        }
        return List.copyOf(result);
    }
}
