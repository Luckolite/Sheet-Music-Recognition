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

    static Capture capture(List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            byte[] labels, byte[] geometryGray, byte[] sourceGray, int width, int height) {
        var candidates = new ArrayList<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (aliasShape(note)) candidates.add(i);
        }
        if (candidates.isEmpty()) return new Capture(notes, List.of());
        var cropCandidates = MeterChangeDetector.candidates(labels, geometryGray, width, height);
        var removed = new java.util.HashSet<Integer>();
        var inlineMeters = new ArrayList<ScoreMeterChange>();
        for (int i : candidates) {
            var note = notes.get(i);
            int bar = note.measureIndex();
            var region = measures.get(bar);
            float x = printedX(note, region, width), y = note.pageY() * height;
            var barHeads = notes.stream().filter(n -> n.measureIndex() == bar && n != note).toList();
            if (barHeads.isEmpty()) continue;
            float nextHead = (float) barHeads.stream().mapToDouble(n -> printedX(n, region, width))
                    .min().orElseThrow();
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

    private static boolean aliasShape(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffIndex() == 0 && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.positionInMeasure() <= .045f && note.stemDirection() == 0
                && note.unbeamedDurationBeats() == 4 && note.beamCount() == 0
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
