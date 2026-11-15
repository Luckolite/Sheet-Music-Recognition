// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0

package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reconciles a nested row proposal with two written separators and its closing quarter dyad. */
final class OverlappingQuarterCadence {
    private OverlappingQuarterCadence() {}

    record Recovery(List<MeasureRegion> measures, byte[] labels) {}

    static Recovery recover(
            List<MeasureRegion> raw,
            List<MeasureRegion> fitted,
            byte[] labels,
            byte[] gray,
            int width,
            int height) {
        if (gray == null
                || labels == null
                || gray.length != (long) width * height
                || labels.length != gray.length) return new Recovery(fitted, labels);
        ArrayList<MeasureRegion> result = null;
        byte[] adjusted = labels;
        for (int i = 0; i + 3 < fitted.size(); i++) {
            var left = fitted.get(i);
            var right = fitted.get(i + 1);
            var nested = fitted.get(i + 2);
            var next = fitted.get(i + 3);
            float gap = (left.bottom() - left.top()) * height / 8.2f;
            if (!nestedTail(raw, left, right, nested, next, width, height, gap)) continue;
            var proposal = proveRules(gray, width, height, left, right, gap);
            if (proposal.isEmpty()) continue;
            var reading = OmrScoreInterpreter.analyze(labels, gray, width, height, proposal);
            if (!reading.rests().isEmpty()
                    || !sixteenthColumns(reading.notes(), proposal.get(0), 0, width, gap)
                    || !sixteenthColumns(reading.notes(), proposal.get(1), 1, width, gap)
                    || !quarterDyad(reading.notes(), proposal.get(2), width, gap)) continue;
            if (result == null) result = new ArrayList<>(fitted);
            adjusted = withoutStaffLabelsOnLowerGuides(adjusted, gray, width, height, proposal);
            for (int j = 0; j < proposal.size(); j++) result.set(i + j, proposal.get(j));
            i += 2;
        }
        return new Recovery(result == null ? fitted : List.copyOf(result), adjusted);
    }

    private static boolean nestedTail(
            List<MeasureRegion> raw,
            MeasureRegion a,
            MeasureRegion b,
            MeasureRegion shadow,
            MeasureRegion next,
            int width,
            int height,
            float gap) {
        if (gap < 8
                || gap > 30
                || a.cadenceQuarterBeats() != 0
                || b.cadenceQuarterBeats() != 0
                || shadow.cadenceQuarterBeats() != 0
                || !sameRow(a, b, height, gap)
                || !sameRow(b, shadow, height, gap)
                || next.top() < b.bottom()
                || shadow.left() <= a.left()
                || shadow.left() >= a.right()
                || shadow.right() <= b.left()
                || shadow.right() >= b.right()
                || (b.left() - a.right()) * width <= 0
                || (b.left() - a.right()) * width > gap
                || (a.right() - a.left()) * width < gap * 35
                || (b.right() - b.left()) * width < gap * 35) return false;
        boolean nestedOriginal = raw.stream().anyMatch(shadow::equals);
        return nestedOriginal
                && raw.stream()
                        .anyMatch(
                                region ->
                                        sameRow(a, region, height, gap)
                                                && Math.abs(region.left() - a.left()) * width
                                                        < gap * .15f
                                                && Math.abs(region.right() - b.right()) * width
                                                        < gap * .15f);
    }

    private static boolean sameRow(MeasureRegion a, MeasureRegion b, int height, float gap) {
        return Math.abs(a.top() - b.top()) * height <= gap * .3f
                && Math.abs(a.bottom() - b.bottom()) * height <= gap * .3f;
    }

    static List<MeasureRegion> proveRules(
            byte[] gray,
            int width,
            int height,
            MeasureRegion left,
            MeasureRegion right,
            float nominalGap) {
        float outer = right.right() * width;
        float bottomHint = right.top() * height + 4 * nominalGap;
        float[] rails =
                PrintedDirectionStaff.printedRules(
                        gray, width, height, outer - nominalGap, bottomHint, nominalGap);
        if (rails == null)
            rails =
                    PrintedDirectionStaff.printedRules(
                            gray,
                            width,
                            height,
                            outer - nominalGap * 3.55f,
                            bottomHint,
                            nominalGap * .92f);
        if (rails == null) return List.of();
        float gap = rails[1];
        if (Math.abs(gap - nominalGap) > nominalGap * .15f) return List.of();
        int top = Math.round(rails[0] - 4 * gap), bottom = Math.round(rails[0]);
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * gap, rails[0], gap, 0, 1);
        int closing =
                PrintedCadenceDurations.boundaryColumn(
                        gray, width, staff, outer + gap * 2.35f, false, false);
        if (closing < outer + gap || closing + gap >= width) return List.of();
        float guessedMiddle = (left.right() + right.left()) * width * .5f;
        int middle =
                rule(
                        gray,
                        width,
                        height,
                        top,
                        bottom,
                        gap,
                        Math.round(guessedMiddle - gap * 4.1f),
                        Math.round(guessedMiddle + gap * 4.1f));
        int cadence =
                rule(
                        gray,
                        width,
                        height,
                        top,
                        bottom,
                        gap,
                        Math.round(closing - gap * 12),
                        Math.round(closing - gap * 8));
        if (middle < 0 || cadence < 0 || cadence - middle < gap * 35) return List.of();

        int padding = Math.round(gap * .5f);
        // Recenter the proposed ownership windows on the five printed rules.
        float topRegion = Math.max(0, top - 2.6f * gap) / height;
        float bottomRegion = Math.min(height - 1, bottom + 2.6f * gap) / height;
        return List.of(
                new MeasureRegion(
                        left.left(), (middle - padding) / (float) width, topRegion, bottomRegion),
                new MeasureRegion(
                        (middle + padding) / (float) width,
                        (cadence - padding) / (float) width,
                        topRegion,
                        bottomRegion),
                new MeasureRegion(
                        (cadence + padding) / (float) width,
                        (closing + padding) / (float) width,
                        topRegion,
                        bottomRegion,
                        1));
    }

    private static int rule(
            byte[] gray,
            int width,
            int height,
            int top,
            int bottom,
            float gap,
            int start,
            int end) {
        if (start < 0 || end >= width) return -1;
        int first = -1, last = -1;
        for (int x = start; x <= end; x++) {
            if (!DottedDyadMeasureSeparator.fullInk(gray, width, x, top, gap)) continue;
            if (last >= 0 && x != last + 1) return -1;
            if (first < 0) first = x;
            last = x;
        }
        if (first < 0 || last - first + 1 < gap * .12f || last - first + 1 > gap * .4f) return -1;
        int center = (first + last) / 2;
        return OmrMeasurePostProcessor.verticalRuleAt(gray, width, center, top, bottom, gap)
                        && !DottedDyadMeasureSeparator.continuesBeyondStaff(
                                gray, width, height, center, top, bottom, gap)
                        && !ClosedHeadBarlineGuard.attached(
                                gray, width, height, center, top, bottom, gap)
                ? center
                : -1;
    }

    private static boolean sixteenthColumns(
            List<ScoreNoteEvent> notes, MeasureRegion region, int index, int width, float gap) {
        var members =
                notes.stream()
                        .filter(note -> note.measureIndex() == index)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        float span = (region.right() - region.left()) * width;
        int count = 0, heads = 0, pairs = 0;
        float column = -1;
        for (var note : members) {
            if (!ordinary(note, 2, 0)) return false;
            if ((note.positionInMeasure() - column) * span > gap * .4f) {
                if (heads == 2) pairs++;
                count++;
                column = note.positionInMeasure();
                heads = 0;
            }
            if (++heads > 2) return false;
        }
        if (heads == 2) pairs++;
        return count == 12 && pairs >= 9;
    }

    private static boolean quarterDyad(
            List<ScoreNoteEvent> notes, MeasureRegion region, int width, float gap) {
        var chord = notes.stream().filter(note -> note.measureIndex() == 2).toList();
        if (chord.size() != 2) return false;
        var a = chord.get(0);
        var b = chord.get(1);
        return ordinary(a, 0, 1)
                && ordinary(b, 0, 1)
                && a.staffStep() != b.staffStep()
                && a.stemDirection() != 0
                && a.stemDirection() == b.stemDirection()
                && !a.tiedFromPrevious()
                && !b.tiedFromPrevious()
                && Math.abs(a.positionInMeasure() - b.positionInMeasure())
                                * (region.right() - region.left())
                                * width
                        <= gap * .35f;
    }

    private static boolean ordinary(ScoreNoteEvent note, int beams, float duration) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.staffIndex() == 0
                && note.staffCount() == 1
                && !note.crossStaffBeam()
                && note.beamCount() == beams
                && note.unbeamedDurationBeats() == duration
                && note.augmentationDots() == 0
                && note.tupletDivisor() == 1
                && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0
                && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    /**
     * A pair of fine horizontal annotation guides can supply two false lower staff lines.
     * Prove both guides span the packed interior but stop before the independent closing
     * double rule. Keep the raw raster and every non-staff label for notation recovery.
     */
    static byte[] withoutStaffLabelsOnLowerGuides(
            byte[] labels, byte[] gray, int width, int height, List<MeasureRegion> regions) {
        var first = regions.get(0);
        var end = regions.get(2);
        float gap = (end.bottom() - end.top()) * height / 9.2f;
        float base = end.bottom() * height - 2.6f * gap;
        int left = Math.round(first.left() * width), right = Math.round(end.right() * width);
        int interiorLeft = left + Math.round(6 * gap), interiorRight = right - Math.round(7 * gap);
        int terminalLeft = right - Math.round(4 * gap),
                terminalRight = right - Math.round(1.25f * gap);
        int firstTop = Math.round(base + gap * .72f), firstBottom = Math.round(base + gap * 1.4f);
        int secondTop = Math.round(base + gap * 1.83f),
                secondBottom = Math.round(base + gap * 2.5f);
        if (left < 0
                || terminalRight >= width
                || firstTop < 1
                || secondBottom + 3 >= height
                || interiorLeft >= interiorRight
                || interiorRight >= terminalLeft) return labels;
        if (!independentGuide(
                        labels,
                        gray,
                        width,
                        interiorLeft,
                        interiorRight,
                        terminalLeft,
                        terminalRight,
                        firstTop,
                        firstBottom,
                        gap)
                || !independentGuide(
                        labels,
                        gray,
                        width,
                        interiorLeft,
                        interiorRight,
                        terminalLeft,
                        terminalRight,
                        secondTop,
                        secondBottom,
                        gap)) return labels;

        byte[] output = labels.clone();
        suppressGuide(output, gray, width, left, terminalLeft, firstTop, firstBottom, gap);
        suppressGuide(output, gray, width, left, terminalLeft, secondTop, secondBottom, gap);
        return output;
    }

    private static boolean independentGuide(
            byte[] labels,
            byte[] gray,
            int width,
            int interiorLeft,
            int interiorRight,
            int terminalLeft,
            int terminalRight,
            int top,
            int bottom,
            float gap) {
        int fine = 0, mapped = 0, terminal = 0;
        for (int x = interiorLeft; x <= interiorRight; x++) {
            if (fineGuideEnd(gray, width, x, top, bottom, gap) < 0) continue;
            fine++;
            for (int y = top - 1; y <= bottom + 3; y++) {
                if (labels[y * width + x] != OmrMeasurePostProcessor.STAFF) continue;
                mapped++;
                break;
            }
        }
        for (int x = terminalLeft; x <= terminalRight; x++)
            if (fineGuideEnd(gray, width, x, top, bottom, gap) >= 0) terminal++;
        int span = interiorRight - interiorLeft + 1;
        return fine >= span * .9f
                && mapped >= span * .65f
                && terminal <= (terminalRight - terminalLeft + 1) * .08f;
    }

    private static int fineGuideEnd(byte[] gray, int width, int x, int top, int bottom, float gap) {
        int first = -1, last = -1;
        for (int y = top; y <= bottom; y++) {
            if ((gray[y * width + x] & 255) >= 155) continue;
            if (last >= 0 && y != last + 1) return -1;
            if (first < 0) first = y;
            last = y;
        }
        return first >= 0 && last - first + 1 <= gap * .28f ? last : -1;
    }

    private static void suppressGuide(
            byte[] labels,
            byte[] gray,
            int width,
            int left,
            int right,
            int top,
            int bottom,
            float gap) {
        int reach = Math.max(3, Math.round(gap * .25f));
        for (int x = left; x < right; x++) {
            int end = fineGuideEnd(gray, width, x, top, bottom, gap);
            if (end < 0) continue;
            for (int y = Math.max(top - 1, end - reach); y <= end + 3; y++) {
                int at = y * width + x;
                if (labels[at] == OmrMeasurePostProcessor.STAFF) labels[at] = 0;
            }
        }
    }
}
