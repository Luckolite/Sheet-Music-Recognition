// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

/** A single dotted half can close a longer meter immediately before a staff change of meter. */
final class IsolatedDottedHalfCadence {
    private IsolatedDottedHalfCadence() {}

    static void apply(ScorePageInterpretation score, byte[] labels, byte[] gray,
            int width, int height, float[] spans, boolean[] cadences) {
        if (score.meterChanges().isEmpty() || gray == null
                || gray.length != (long) width * height) return;
        var measures = score.measures();
        List<PlayingTechniqueDetector.Staff> staffs = null;
        for (var change : score.meterChanges()) {
            int bar = change.measureIndex() - 1;
            if (bar < 1 || bar >= spans.length - 1 || spans[bar] <= 3 || cadences[bar]) continue;
            if (change.quarterBeats() == spans[bar]) continue;
            var owners = score.notes().stream()
                    .filter(note -> note.measureIndex() == bar).toList();
            if (owners.size() != 1 || !admissible(owners.get(0))
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == bar)) continue;
            if (staffs == null)
                staffs = OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, measures);
            if (proved(gray, width, height, measures.get(bar - 1), measures.get(bar), measures.get(bar + 1),
                    owners.get(0), staffs)) {
                spans[bar] = 3;
                cadences[bar] = true;
            }
        }
    }

    private static boolean admissible(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffIndex() == 0 && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.unbeamedDurationBeats() == 2 && note.augmentationDots() == 1
                && note.beamCount() == 0 && note.stemDirection() != 0
                && note.tupletDivisor() == 1 && !note.crossStaffBeam()
                && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean proved(byte[] gray, int width, int height, MeasureRegion previous,
            MeasureRegion current, MeasureRegion next, ScoreNoteEvent note, List<PlayingTechniqueDetector.Staff> staffs) {
        float left = current.left() * width, right = current.right() * width;
        float span = right - left, position = note.positionInMeasure();
        if (position < .25f || position > .70f || next.top() <= current.bottom()) return false;
        float center = left + span * position;
        for (var fitted : staffs) {
            float gap = fitted.gap();
            if (fitted.index() != 0 || fitted.count() != 1 || gap < 6 || span > gap * 8
                    || fitted.top() < current.top() * height - gap
                    || fitted.bottom() > current.bottom() * height + gap) continue;
            var rules = PrintedDirectionStaff.printedRules(
                    gray, width, height, (left + right) * .5f, fitted.bottom(), gap);
            boolean terminalFrame = rules == null;
            if (terminalFrame) {
                // The bilateral sample may run beyond the printed staff at a short close.
                // Admit the existing one-sided reader only across an adjacent same-staff seam.
                float separation = (current.left() - previous.right()) * width;
                if (separation < 0 || separation > gap * 1.5f
                        || Math.abs(previous.top() - current.top()) * height > gap * .5f
                        || Math.abs(previous.bottom() - current.bottom()) * height > gap * .5f
                        || span > gap * 5) continue;
                rules = TerminalDirectionRails.read(
                        gray, width, height, (left + right) * .5f, fitted.bottom(), gap,
                        previous.left(), current.right());
            }
            if (rules == null) continue;
            gap = rules[1];
            float top = rules[0] - 4 * gap;
            if (top < 0 || rules[0] >= height) continue;
            var staff = new PlayingTechniqueDetector.Staff(top, rules[0], gap, 0, 1);
            int opening = PrintedCadenceDurations.boundaryColumn(
                    gray, width, staff, left - gap * .5f, true, false);
            int closing = PrintedCadenceDurations.boundaryColumn(
                    gray, width, staff, right + gap * .45f, false, false);
            // A barline column is its first ink pixel, and the preceding fitted box is
            // inset. Allow a small left-side tolerance only in the proved terminal frame.
            float openingClearance = terminalFrame ? 1.85f : 2;
            if (opening < 0 || closing < 0 || center - opening < gap * openingClearance
                    || closing - center < gap * 2) continue;
            return PrintedCadenceDurations.emptyOutsideChord(gray, width, staff,
                    Math.max(left, opening + gap * .65f), closing + gap * .45f,
                    center, center, note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
        }
        return false;
    }
}
