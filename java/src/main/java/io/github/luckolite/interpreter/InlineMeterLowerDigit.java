// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;
import java.util.Set;

/** A lower time-signature digit can be segmented as a hollow, stemmed note. */
final class InlineMeterLowerDigit {
    private InlineMeterLowerDigit() {}

    static boolean candidate(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && note.stemDirection() == 1
                && note.beamCount() == 0 && note.unbeamedDurationBeats() == 2
                && note.positionInMeasure() <= .048f && note.tupletDivisor() == 1
                && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && note.articulations() == 0 && note.augmentationDots() == 0
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && !note.tiedFromPrevious() && note.boundaryTies() == 0 && !note.crossStaffBeam();
    }

    static ScoreMeterChange recover(ScoreNoteEvent alias, List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures, byte[] labels, byte[] gray, byte[] original,
            int width, int height) {
        int bar = alias.measureIndex();
        if (bar <= 0 || bar >= measures.size() || gray == null || labels == null || original == null
                || gray.length != (long) width * height || labels.length != gray.length
                || original.length != gray.length) return null;
        var previous = measures.get(bar - 1);
        var current = measures.get(bar);
        float guess = (current.bottom() - current.top()) * height / 8.2f;
        if (guess < 8 || guess > 30 || Math.abs(current.top() - previous.top()) * height > guess * .4f
                || previous.right() >= current.left()) return null;
        float x = printedX(alias, current, width), y = alias.pageY() * height;
        var owners = notes.stream().filter(n -> n != alias && n.measureIndex() == bar).toList();
        float first = (float) owners.stream().mapToDouble(n -> printedX(n, current, width))
                .min().orElse(Float.POSITIVE_INFINITY);
        if (first - x < guess * 2.65f || first - x > guess * 4.75f) return null;
        float hint = current.bottom() * height - 2.1f * guess;
        var rules = PrintedDirectionStaff.printedRules(gray, width, height, first, hint, guess);
        var sourceRules = PrintedDirectionStaff.printedRules(original, width, height, first, hint, guess);
        if (rules == null || sourceRules == null || Math.abs(rules[0] - sourceRules[0]) > guess * .22f
                || Math.abs(rules[1] - sourceRules[1]) > guess * .12f) return null;

        float gap = rules[1];
        int top = Math.round(rules[0] - 4 * gap);
        if (y < top + 2.12f * gap || y > top + 3.48f * gap || top < gap || rules[0] + gap >= height)
            return null;
        float seam = (previous.right() + current.left()) * width * .5f;
        var staff = new PlayingTechniqueDetector.Staff(top, rules[0], gap, 0, 1);
        int boundary = PrintedCadenceDurations.boundaryColumn(gray, width, staff, seam, true, false);
        int originalBoundary = PrintedCadenceDurations.boundaryColumn(original, width, staff, seam, true, false);
        if (boundary < 0 || originalBoundary < 0 || Math.abs(boundary - originalBoundary) > gap * .22f
                || x - boundary < gap * .52f || x - boundary > gap * 1.5f) return null;

        int left = Math.max(boundary, originalBoundary) + Math.max(2, Math.round(gap * .23f));
        int right = Math.round(Math.min(left + 2.2f * gap, first - gap * 1.45f));
        var crop = new MeterChangeDetector.Crop(left, Math.round(top - gap * .22f),
                right, Math.round(rules[0] + gap * .35f), top, gap);
        if (right - left < gap * 1.3f || x < left || x >= right
                || MeterChangeDetector.followingMeasure(crop, width, height, measures) != bar
                || !MeterChangeDetector.precedesNotes(crop, labels, gray, width, height, bar, measures, owners)
                || !MeterChangeDetector.precedesNotes(crop, labels, original, width, height, bar, measures, owners))
            return null;
        String number = read(gray, width, height, crop);
        if (number.isBlank() || !number.equals(read(original, width, height, crop))) return null;
        var digits = number.split("/");
        return new ScoreMeterChange(bar, Integer.parseInt(digits[0]), Integer.parseInt(digits[1]));
    }

    private static float printedX(ScoreNoteEvent note, MeasureRegion region, int width) {
        return (region.left() + note.positionInMeasure() * (region.right() - region.left())) * width;
    }

    private static String read(byte[] gray, int width, int height, MeterChangeDetector.Crop crop) {
        var reading = MeterFontMatcher.readCrop(gray, width, height, crop);
        return MusicalOcrEvidence.fontMeter(
                reading.text(), reading.topScore(), reading.bottomScore(), Set.of(), Set.of());
    }
}
