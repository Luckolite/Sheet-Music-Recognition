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

    /** A dot joined to the staff rule can escape the notehead rhythm detector. */
    static boolean provesMissingDot(
            byte[] labels, byte[] gray, byte[] annotation, int width, int height,
            MeasureRegion previous, MeasureRegion current, MeasureRegion next,
            ScoreNoteEvent note, List<PlayingTechniqueDetector.Staff> staffs) {
        if (labels == null || labels.length != (long) width * height || annotation == null
                || next.top() <= current.bottom()
                || note.visualSize() != ScoreNoteEvent.VisualSize.FULL || note.stemDirection() == 0
                || note.positionInMeasure() < .25f || note.positionInMeasure() > .70f) return false;
        float left = current.left() * width, right = current.right() * width;
        float x = left + (right - left) * note.positionInMeasure(), y = note.pageY() * height;
        for (var fitted : staffs) {
            float gap = fitted.gap();
            float separation = (current.left() - previous.right()) * width;
            if (fitted.index() != 0 || fitted.count() != 1 || gap < 10
                    || right - left > gap * 8
                    || fitted.top() < current.top() * height - gap
                    || fitted.bottom() > current.bottom() * height + gap
                    || Math.abs(previous.top() - current.top()) * height > gap * .5f
                    || Math.abs(previous.bottom() - current.bottom()) * height > gap * .5f
                    || separation < 0 || separation > gap * 1.5f) continue;
            boolean accidental = note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
            var dot = attachedDot(gray, width, height, left, right, x, y, fitted, accidental);
            if (dot == null || labels[dot.y() * width + dot.x()] != OmrMeasurePostProcessor.SYMBOL)
                continue;
            var original = attachedDot(
                    annotation, width, height, left, right, x, y, fitted, accidental);
            if (original != null && Math.abs(dot.x() - original.x()) <= gap * .15f
                    && Math.abs(dot.y() - original.y()) <= gap * .15f
                    && labels[original.y() * width + original.x()] == OmrMeasurePostProcessor.SYMBOL)
                return true;
        }
        return false;
    }

    private static WrappedTripletDotInk.Center attachedDot(
            byte[] gray, int width, int height, float left, float right, float x, float y,
            PlayingTechniqueDetector.Staff fitted, boolean accidental) {
        var rules = PrintedDirectionStaff.printedRules(
                gray, width, height, x, fitted.bottom(), fitted.gap());
        if (rules == null) return null;
        float gap = rules[1], top = rules[0] - 4 * gap;
        if (top < 0 || rules[0] >= height) return null;
        var staff = new PlayingTechniqueDetector.Staff(top, rules[0], gap, 0, 1);
        int opening = PrintedCadenceDurations.boundaryColumn(
                gray, width, staff, left - gap * .5f, true, false);
        int closing = PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, true);
        if (opening < 0 || closing < 0 || x - opening < gap * 1.7f || closing - x < gap * 2.3f)
            return null;
        var dot = WrappedTripletDotInk.dottedHalfCore(gray, width, height, x, y, closing, gap);
        if (dot == null || !PrintedCadenceDurations.emptyOutsideChord(
                gray, width, staff, Math.max(left, opening + gap * .65f), closing + gap * .45f,
                x, x, accidental)) return null;
        return dot;
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
        if (position < .12f || position > .70f || next.top() <= current.bottom()) return false;
        float center = left + span * position;
        for (var fitted : staffs) {
            float gap = fitted.gap();
            if (fitted.index() != 0 || fitted.count() != 1 || gap < 6 || span > gap * 8
                    || fitted.top() < current.top() * height - gap
                    || fitted.bottom() > current.bottom() * height + gap) continue;
            if (position < .25f) {
                if (provedTightFrame(gray, width, height, previous, current, note, fitted, left, right, center))
                    return true;
                continue;
            }
            var rules = PrintedDirectionStaff.printedRules(
                    gray, width, height, (left + right) * .5f, fitted.bottom(), gap);
            boolean terminalFrame = rules == null;
            boolean headCenteredFrame = false;
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
                if (rules == null) {
                    // A left-weighted cadence can have all five rails at its head even
                    // when the bilateral sample at its midpoint exceeds the staff end.
                    rules =
                            PrintedDirectionStaff.printedRules(
                                    gray, width, height, center, fitted.bottom(), gap);
                    headCenteredFrame = true;
                }
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
            LegacyTripletDotInk.Core printedDot = null;
            if (closing < 0) {
                // The fitted region can already end on the outer edge of the printed double.
                closing = PrintedCadenceDurations.boundaryColumn(
                        gray, width, staff, right, false, false);
                if (closing < 0) continue;
                printedDot = LegacyTripletDotInk.uniqueDotCore(gray, width, height, center,
                        note.pageY() * height, Math.min(closing, center + gap * 3), gap);
                if (printedDot == null) continue;
            }
            // A barline column is its first ink pixel, and the preceding fitted box is
            // inset. Allow a small left-side tolerance only in the proved terminal frame.
            float openingClearance = headCenteredFrame ? 1.7f : terminalFrame ? 1.85f : 2;
            if (opening < 0 || closing < 0 || center - opening < gap * 1.70f
                    || closing - center < gap * 2) continue;
            boolean compactOpening = center - opening < gap * openingClearance;
            if (compactOpening
                    && PrintedCadenceDurations.boundaryColumn(
                            gray, width, staff, right + gap * .45f, false, true) < 0) continue;
            if ((headCenteredFrame || compactOpening)
                    && !LegacyTripletDotInk.singleDotCore(
                            gray, width, height, center, note.pageY() * height, closing, gap))
                continue;
            float insideLeft = Math.max(left, opening + gap * .65f);
            float insideRight = closing + gap * .45f;
            boolean accidental = note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
            if (PrintedCadenceDurations.emptyOutsideChord(gray, width, staff, insideLeft,
                    insideRight, center, center, accidental, printedDot)) return true;
            if (printedDot != null) return false;
            // A round dot can account for a tall column even when the closing rule was found directly.
            printedDot = LegacyTripletDotInk.uniqueDotCore(gray, width, height, center,
                    note.pageY() * height, Math.min(closing, center + gap * 3), gap);
            return printedDot != null
                    && PrintedCadenceDurations.emptyOutsideChord(gray, width, staff, insideLeft,
                            insideRight, center, center, accidental, printedDot);
        }
        return false;
    }

    /** An inset boundary can leave only a few staff spaces around the printed cadence. */
    private static boolean provedTightFrame(byte[] gray, int width, int height,
            MeasureRegion previous, MeasureRegion current, ScoreNoteEvent note,
            PlayingTechniqueDetector.Staff fitted, float left, float right, float center) {
        float gap = fitted.gap();
        float separation = (current.left() - previous.right()) * width;
        if (right - left > gap * 4
                || separation < 0 || separation > gap * 1.5f
                || Math.abs(previous.top() - current.top()) * height > gap * .5f
                || Math.abs(previous.bottom() - current.bottom()) * height > gap * .5f) return false;
        float railQuery = right - gap * 1.5f;
        if (railQuery - gap * .85f <= center || railQuery + gap * .85f >= right) return false;
        var rules = DottedDyadMeasureSeparator.localRails(
                gray, width, height, Math.round(railQuery), fitted.bottom(), gap);
        if (rules == null || rules[1] < 6) return false;
        gap = rules[1];
        float top = rules[0] - 4 * gap;
        if (top < 0 || rules[0] >= height) return false;
        var staff = new PlayingTechniqueDetector.Staff(top, rules[0], gap, 0, 1);
        int opening = PrintedCadenceDurations.boundaryColumn(
                gray, width, staff, left - gap * .5f, true, false);
        int closing = PrintedCadenceDurations.boundaryColumn(
                gray, width, staff, right + gap * .45f, false, false);
        if (opening < 0 || closing < 0 || center - opening < gap * 1.1f
                || closing - center < gap * 2.25f) return false;
        if (WrappedTripletDotInk.dottedHalfCore(
                gray, width, height, center, note.pageY() * height, closing, gap) == null) return false;
        return PrintedCadenceDurations.emptyOutsideChord(gray, width, staff,
                Math.max(left, opening + gap * .65f), closing + gap * .45f,
                center, center, note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
    }
}
