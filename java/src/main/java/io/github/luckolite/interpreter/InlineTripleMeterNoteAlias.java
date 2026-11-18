// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** A complete inline signature owns numerator ink that was segmented as an isolated grace head. */
final class InlineTripleMeterNoteAlias {
    private InlineTripleMeterNoteAlias() {}

    static List<ScoreNoteEvent> remove(
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
            byte[] labels,
            byte[] gray,
            byte[] original,
            int width,
            int height) {
        List<MeterChangeDetector.Crop> crops = null;
        var removed = new java.util.HashSet<ScoreNoteEvent>();
        for (var note : notes) {
            int bar = note.measureIndex();
            if (bar < 1
                    || bar >= measures.size()
                    || note.positionInMeasure() > .08f
                    || note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.staffCount() != 1
                    || note.staffIndex() != 0
                    || note.stemDirection() != 0
                    || note.beamCount() != 0
                    || note.unbeamedDurationBeats() != 1
                    || note.augmentationDots() != 0
                    || note.tupletDivisor() != 1
                    || note.articulations() != NoteOrnament.GRACE
                    || note.tiedFromPrevious()
                    || note.boundaryTies() != 0
                    || note.leadingRestBeats() != 0
                    || note.followingRestBeats() != 0
                    || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) continue;
            var current = measures.get(bar);
            var previous = measures.get(bar - 1);
            if (current.top() >= previous.bottom()) continue;
            var heads =
                    notes.stream()
                            .filter(n -> n.measureIndex() == bar && n != note)
                            .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                            .toList();
            if (heads.size() != 24) continue;
            if (crops == null) crops = MeterChangeDetector.candidates(labels, gray, width, height);
            float left = current.left() * width, span = (current.right() - current.left()) * width;
            float x = left + span * note.positionInMeasure(), y = height * note.pageY();
            float seam = (current.left() + previous.right()) * width * .5f;
            for (var crop : crops) {
                float gap = crop.gap();
                if (gap < 8
                        || gap > 28
                        || crop.right() - crop.left() > gap * 2.4f
                        || Math.abs(x - (crop.left() + crop.right()) * .5f) > gap * .55f
                        || Math.abs(y - (crop.firstLine() + 1.5f * gap)) > gap * .6f
                        || left + span * heads.get(0).positionInMeasure() - crop.right()
                                < gap * 1.65f
                        || MeterChangeDetector.followingMeasure(crop, width, height, measures)
                                != bar
                        || !MeterChangeDetector.precedesNotes(
                                crop, labels, gray, width, height, bar, measures, heads)
                        || !MeterNumeralWholeNoteAlias.connectedToNoteheadLabels(
                                labels, gray, width, crop, x, y)
                        || !octaves(heads, span, gap)) continue;
                var staff =
                        new PlayingTechniqueDetector.Staff(
                                crop.firstLine(), crop.firstLine() + 4 * gap, gap, 0, 1);
                if (staff.top() < 0 || staff.bottom() >= height) continue;
                if (!provesPrintedTriple(crop, gray, width, height, staff, seam)
                        || !provesPrintedTriple(crop, original, width, height, staff, seam)) continue;
                removed.add(note);
                break;
            }
        }
        if (removed.isEmpty()) return notes;
        var kept = new ArrayList<>(notes);
        kept.removeAll(removed);
        return List.copyOf(kept);
    }

    private static boolean octaves(List<ScoreNoteEvent> heads, float span, float gap) {
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            if (!sixteenth(a)
                    || !sixteenth(b)
                    || a.stemDirection() != b.stemDirection()
                    || Math.abs(a.staffStep() - b.staffStep()) != 7
                    || (b.positionInMeasure() - a.positionInMeasure()) * span > gap * .18f)
                return false;
        }
        return true;
    }

    private static boolean sixteenth(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffIndex() == 0
                && note.staffCount() == 1
                && note.stemDirection() != 0
                && note.beamCount() == 2
                && note.augmentationDots() == 0
                && note.tupletDivisor() == 1
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    static boolean provesPrintedTriple(
            MeterChangeDetector.Crop crop,
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff,
            float seam) {
        if (PrintedCadenceDurations.boundaryColumn(gray, width, staff, seam, false, false) < 0)
            return false;
        var read = MeterFontMatcher.readCrop(gray, width, height, crop);
        return "3/4"
                .equals(
                        MusicalOcrEvidence.fontMeter(
                                read.text(),
                                read.topScore(),
                                read.bottomScore(),
                                Set.of(),
                                Set.of()));
    }
}
