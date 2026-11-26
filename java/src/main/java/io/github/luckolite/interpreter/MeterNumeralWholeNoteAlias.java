// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Releases a stemless whole-note alias when a complete meter owns the same pixels. */
final class MeterNumeralWholeNoteAlias {
    private MeterNumeralWholeNoteAlias() {}

    record Capture(List<ScoreNoteEvent> notes, List<ScoreMeterChange> inlineMeters) {}

    @FunctionalInterface
    interface NumericReader {
        String read(byte[] gray, int width, int height, MeterChangeDetector.Crop crop);
    }

    static Capture capture(List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            byte[] labels, byte[] geometryGray, byte[] sourceGray, int width, int height) {
        return capture(notes, measures, labels, geometryGray, sourceGray, width, height, null);
    }

    static Capture capture(List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            byte[] labels, byte[] geometryGray, byte[] sourceGray, int width, int height,
            NumericReader numericReader) {
        var candidates = new ArrayList<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (InlineMeterLowerDigit.candidate(note) || aliasShape(note) || numericReader != null && historicalDenominatorAlias(note)
                    || stemlessQuarterAlias(note)
                    && note.measureIndex() > 0 && beginsStaff(measures, note.measureIndex()))
                candidates.add(i);
        }
        if (candidates.isEmpty()) return new Capture(notes, List.of());
        var cropCandidates = MeterChangeDetector.candidates(labels, geometryGray, width, height);
        var removed = new java.util.HashSet<Integer>();
        var inlineMeters = new ArrayList<ScoreMeterChange>();
        for (int i : candidates) {
            var note = notes.get(i);
            int bar = note.measureIndex();
            if (InlineMeterLowerDigit.candidate(note)) {
                var change = InlineMeterLowerDigit.recover(
                        note, notes, measures, labels, geometryGray, sourceGray, width, height);
                if (change != null) {
                    removed.add(i);
                    inlineMeters.add(change);
                }
                continue;
            }
            if (historicalDenominatorAlias(note)) {
                if (numericReader != null && bar > 0 && bar < measures.size()
                        && provesInlineThreeEight(notes, note, bar, measures, labels, geometryGray,
                                sourceGray, width, height, numericReader)) {
                    removed.add(i);
                    inlineMeters.add(new ScoreMeterChange(bar, 3, 8));
                }
                continue;
            }
            var region = measures.get(bar);
            float x = printedX(note, region, width), y = note.pageY() * height;
            var barHeads = notes.stream().filter(n -> n.measureIndex() == bar && n != note).toList();
            if (barHeads.isEmpty()) continue;
            float nextHead = (float) barHeads.stream().mapToDouble(n -> printedX(n, region, width))
                    .min().orElseThrow();
            if (stemlessQuarterAlias(note)) {
                if (openingTwoFour(labels, geometryGray, width, height, measures, barHeads, bar,
                                x, y, nextHead)
                        && openingTwoFour(labels, sourceGray, width, height, measures, barHeads, bar,
                                x, y, nextHead)) {
                    removed.add(i);
                    inlineMeters.add(new ScoreMeterChange(bar, 2, 4));
                }
                continue;
            }
            if (!beginsStaff(measures, bar)) {
                if (provesInlineTriple(cropCandidates, labels, geometryGray, sourceGray, width,
                        height, measures, barHeads, bar, x, y, nextHead)) {
                    removed.add(i);
                    inlineMeters.add(new ScoreMeterChange(bar, 3, 4));
                }
                continue;
            }
            for (var crop : cropCandidates) {
                float gap = crop.gap();
                if (gap < 6 || gap > 32 || crop.right() - crop.left() > gap * 2.35f
                        || Math.abs(x - (crop.left() + crop.right()) * .5f) > gap * .52f
                        || Math.abs(y - (crop.firstLine() + 1.5f * gap)) > gap * .62f
                        || nextHead - crop.right() < gap * 1.65f
                        || MeterChangeDetector.followingMeasure(crop, width, height, measures) != bar
                        || !connectedToNoteheadLabels(labels, geometryGray, width, crop, x, y)
                        || !MeterChangeDetector.precedesNotes(
                                crop, labels, geometryGray, width, height, bar, measures, barHeads)) continue;
                var read = MeterFontMatcher.readCrop(geometryGray, width, height, crop);
                if (!proved(read)) continue;
                var sourceRead = MeterFontMatcher.readCrop(sourceGray, width, height, crop);
                if (!proved(sourceRead) || !sourceRead.text().equals(read.text())) continue;
                removed.add(i);
                break;
            }
        }
        if (removed.isEmpty()) return new Capture(notes, List.of());
        var retained = new ArrayList<ScoreNoteEvent>(notes.size() - removed.size());
        for (int i = 0; i < notes.size(); i++) {
            if (removed.contains(i)) continue;
            retained.add(notes.get(i));
        }
        return new Capture(List.copyOf(retained), List.copyOf(inlineMeters));
    }

    /** A system-opening meter can supply a false stemless quarter on the upper numeral. */
    private static boolean openingTwoFour(
            byte[] labels, byte[] gray, int width, int height, List<MeasureRegion> measures,
            List<ScoreNoteEvent> barHeads, int bar, float x, float y, float nextHead) {
        var region = measures.get(bar);
        float nominal = (region.bottom() - region.top()) * height / 8.2f;
        if (gray == null || gray.length != labels.length || nominal < 10 || nominal > 26) return false;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, x + nominal * 2.5f,
                region.bottom() * height - 2.1f * nominal, nominal);
        if (rails == null) return false;
        float gap = rails[1];
        int firstLine = Math.round(rails[0] - 4 * gap);
        float inset = x - region.left() * width;
        if (inset < gap * 1.45f || inset > gap * 2.85f
                || Math.abs(y - (firstLine + 1.5f * gap)) > gap * .35f) return false;
        int left = Math.round(x - gap * 1.35f), right = Math.round(x + gap * 1.35f);
        int top = firstLine - Math.round(gap * .20f);
        int bottom = firstLine + Math.round(gap * 4.25f);
        if (left < 0 || right > width || top < 0 || bottom > height
                || nextHead - right < gap * 1.65f) return false;
        var crop = new MeterChangeDetector.Crop(left, top, right, bottom, firstLine, gap);
        if (MeterChangeDetector.followingMeasure(crop, width, height, measures) != bar
                || !connectedToNoteheadLabels(labels, gray, width, crop, x, y)
                || !MeterChangeDetector.precedesNotes(crop, labels, gray, width, height,
                        bar, measures, barHeads)) return false;
        var reading = MeterFontMatcher.readCrop(gray, width, height, crop);
        return reading.text().equals("2/4") && reading.topScore() >= .87f
                && reading.bottomScore() >= .87f;
    }

    private static boolean provesInlineTriple(
            List<MeterChangeDetector.Crop> candidates,
            byte[] labels,
            byte[] geometryGray,
            byte[] sourceGray,
            int width,
            int height,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> barHeads,
            int bar,
            float x,
            float y,
            float nextHead) {
        var current = measures.get(bar);
        var previous = measures.get(bar - 1);
        float seam = (previous.right() + current.left()) * width * .5f;
        for (var rule : candidates) {
            float gap = rule.gap();
            if (gap < 8 || gap > 32
                    || Math.abs(previous.top() - current.top()) * height > gap * .35f
                    || seam < rule.left() || seam >= rule.right()
                    || rule.right() - rule.left() > gap * 1.25f
                    || rule.right() > x - gap * .35f
                    || rule.right() < x - gap
                    || Math.abs(y - (rule.firstLine() + 1.5f * gap)) > gap * .62f
                    || MeterChangeDetector.followingMeasure(rule, width, height, measures) != bar)
                continue;
            int left = rule.right();
            int right = Math.round(nextHead - gap * 1.4f);
            if (right - left < gap * 1.3f || right - left > gap * 2.75f) continue;
            var crop = new MeterChangeDetector.Crop(
                    left, rule.top(), right, rule.bottom(), rule.firstLine(), gap);
            if (MeterChangeDetector.followingMeasure(crop, width, height, measures) != bar
                    || !connectedToNoteheadLabels(labels, geometryGray, width, crop, x, y)
                    || !MeterChangeDetector.precedesNotes(
                            crop, labels, geometryGray, width, height, bar, measures, barHeads))
                continue;
            var staff = new PlayingTechniqueDetector.Staff(
                    crop.firstLine(), crop.firstLine() + 4 * gap, gap, 0, 1);
            if (staff.top() >= 0 && staff.bottom() < height
                    && InlineTripleMeterNoteAlias.provesPrintedTriple(
                            crop, geometryGray, width, height, staff, seam)
                    && InlineTripleMeterNoteAlias.provesPrintedTriple(
                            crop, sourceGray, width, height, staff, seam)) return true;
        }
        return false;
    }

    private static boolean historicalDenominatorAlias(ScoreNoteEvent note) {
        return note.positionInMeasure() >= .15f && note.positionInMeasure() <= .6f
                && note.unbeamedDurationBeats() == 4 && stemlessHead(note);
    }

    private static boolean provesInlineThreeEight(
            List<ScoreNoteEvent> notes, ScoreNoteEvent alias, int bar, List<MeasureRegion> measures,
            byte[] labels, byte[] gray, byte[] source, int width, int height,
            NumericReader reader) {
        if (bar + 1 >= measures.size()) return false;
        var current = measures.get(bar);
        var previous = measures.get(bar - 1);
        var next = measures.get(bar + 1);
        float gap = (current.bottom() - current.top()) * height / 8.2f;
        float cx = printedX(alias, current, width), cy = alias.pageY() * height;
        float left = current.left() * width;
        if (gap < 8 || gap > 28 || Math.abs(current.top() - previous.top()) * height > gap * .4f
                || Math.abs(current.top() - next.top()) * height > gap * .4f
                || cx - left < gap * 4.5f || cx - left > gap * 8) return false;
        var owners = notes.stream().filter(n -> n.measureIndex() == bar && n != alias).toList();
        var following = notes.stream().filter(n -> n.measureIndex() == bar + 1).toList();
        if (!closedSixteenthBar(owners) || !closedSixteenthBar(following)) return false;
        float first = (float) owners.stream().mapToDouble(n -> printedX(n, current, width)).min().orElseThrow();
        if (first - cx < gap * 1.8f || first - cx > gap * 2.7f) return false;

        float hint = current.bottom() * height - 2.1f * gap;
        float staffX = (left + cx) * .5f;
        var rail = PrintedDirectionStaff.printedRules(gray, width, height, staffX, hint, gap);
        var original = PrintedDirectionStaff.printedRules(source, width, height, staffX, hint, gap);
        if (rail == null || original == null || Math.abs(rail[0] - original[0]) > gap * .25f
                || Math.abs(rail[1] - original[1]) > gap * .10f) return false;
        gap = rail[1];
        int top = Math.round(rail[0] - 4 * gap);
        if (cy < top + gap * 2.2f || cy > top + gap * 3.15f) return false;
        var crop = new MeterChangeDetector.Crop(Math.round(cx - gap * .75f),
                Math.round(top - gap * .20f), Math.round(Math.min(first - gap * .55f, cx + gap * 1.35f)),
                Math.round(rail[0] + gap * .34f), top, gap);
        if (crop.left() < 0 || crop.right() > width || crop.top() < 0 || crop.bottom() > height
                || crop.right() - crop.left() < gap * 1.7f
                || !connectedToNoteheadLabels(labels, gray, width, crop, cx, cy)
                || !connectedToNoteheadLabels(labels, source, width, crop, cx, cy)
                || MeterChangeDetector.followingMeasure(crop, width, height, measures) != bar
                || !MeterChangeDetector.precedesNotes(crop, labels, gray, width, height, bar, measures, owners))
            return false;
        var staff = new PlayingTechniqueDetector.Staff(top, rail[0], gap, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, width, staff, left, true, false) < 0
                || PrintedCadenceDurations.boundaryColumn(source, width, staff, left, true, false) < 0) return false;
        return "3/8".equals(reader.read(gray, width, height, crop))
                && (gray == source || "3/8".equals(reader.read(source, width, height, crop)));
    }

    private static boolean closedSixteenthBar(List<ScoreNoteEvent> notes) {
        return notes.size() == 6 && notes.stream().allMatch(n -> n.staffIndex() == 0 && n.staffCount() == 1
                && n.kind() == ScoreNoteEvent.Kind.PITCHED && n.beamCount() == 2
                && n.augmentationDots() == 0 && !n.crossStaffBeam()
                && (n.articulations() & NoteOrnament.GRACE) == 0);
    }

    private static boolean aliasShape(ScoreNoteEvent note) {
        return note.positionInMeasure() <= .045f && note.unbeamedDurationBeats() == 4
                && stemlessHead(note);
    }

    private static boolean stemlessQuarterAlias(ScoreNoteEvent note) {
        return note.positionInMeasure() <= .20f && note.unbeamedDurationBeats() == 1
                && note.harmonicSemitones() == 0 && note.octaveShift() == 0
                && stemlessHead(note);
    }

    private static boolean stemlessHead(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffIndex() == 0 && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.stemDirection() == 0 && note.beamCount() == 0
                && note.augmentationDots() == 0 && note.tupletDivisor() == 1
                && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && !note.crossStaffBeam() && note.articulations() == 0;
    }

    private static boolean beginsStaff(List<MeasureRegion> measures, int index) {
        return index == 0 || measures.get(index).top() > measures.get(index - 1).bottom();
    }

    private static float printedX(ScoreNoteEvent head, MeasureRegion measure, int width) {
        return (measure.left() + head.positionInMeasure() * (measure.right() - measure.left())) * width;
    }

    private static boolean proved(MeterFontMatcher.Reading reading) {
        return !MusicalOcrEvidence.fontMeter(reading.text(), reading.topScore(),
                reading.bottomScore(), Set.of(), Set.of()).isBlank();
    }

    static boolean connectedToNoteheadLabels(
            byte[] labels,
            byte[] gray,
            int width,
            MeterChangeDetector.Crop crop,
            float x,
            float y) {
        int radiusX = Math.round(crop.gap() * .5f), radiusY = Math.round(crop.gap() * .4f);
        int ink = 0;
        for (int yy = Math.max(crop.top(), Math.round(y) - radiusY);
                yy < Math.min(crop.bottom(), Math.round(y) + radiusY + 1); yy++) {
            for (int xx = Math.max(crop.left(), Math.round(x) - radiusX);
                    xx < Math.min(crop.right(), Math.round(x) + radiusX + 1); xx++) {
                int at = yy * width + xx;
                if (labels[at] == OmrMeasurePostProcessor.NOTEHEAD && (gray[at] & 255) < 160) ink++;
            }
        }
        return ink >= Math.max(3, Math.round(crop.gap() * .35f));
    }
}
