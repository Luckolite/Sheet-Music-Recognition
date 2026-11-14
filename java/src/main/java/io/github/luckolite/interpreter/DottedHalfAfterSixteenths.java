// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Separates a physically enclosed dotted half from a complete sixteenth-note run. */
final class DottedHalfAfterSixteenths {
    private DottedHalfAfterSixteenths() {}

    static List<MeasureRegion> recover(
            List<MeasureRegion> regions, byte[] labels, byte[] gray, int width, int height) {
        if (width < 1
                || height < 1
                || labels == null
                || gray == null
                || gray.length != (long) width * height
                || labels.length != gray.length) return regions;
        List<MeasureRegion> result = null;
        for (int i = 1; i + 1 < regions.size(); i++) {
            var region = regions.get(i);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            if (region.cadenceQuarterBeats() != 0
                    || gap < 10
                    || gap > 25
                    || (regions.get(i + 1).top() - region.bottom()) * height < gap * 2
                    || Math.abs(regions.get(i - 1).bottom() - region.bottom()) * height
                            > gap * .55f) continue;
            var proposed = split(region, labels, gray, width, height, gap);
            if (proposed.isEmpty()) continue;
            if (result == null) result = new ArrayList<>(regions);
            int at = result.indexOf(region);
            result.set(at, proposed.get(0));
            result.add(at + 1, proposed.get(1));
        }
        return result == null ? regions : List.copyOf(result);
    }

    private static List<MeasureRegion> split(
            MeasureRegion region, byte[] labels, byte[] gray, int width, int height, float gap) {
        float left = region.left() * width, right = region.right() * width;
        float hint = region.bottom() * height - gap * 2.1f;
        if (left < 0 || right >= width || right - left < gap * 33 || right - left > gap * 47)
            return List.of();
        var rails =
                PrintedDirectionStaff.printedRules(
                        gray, width, height, right - gap * 2.5f, hint, gap);
        if (rails == null) return List.of();
        gap = rails[1];
        int top = Math.round(rails[0] - 4 * gap), bottom = Math.round(rails[0]);
        if (top < gap || bottom + gap >= height) return List.of();
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * gap, rails[0], gap, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, true) < 0)
            return List.of();

        int first = Math.round(right - gap * 9.5f), last = Math.round(right - gap * 7f), axis = -1;
        for (int x = first; x <= last; x++) {
            if (!DottedDyadMeasureSeparator.fullInk(gray, width, x, top, gap)) continue;
            int begin = x;
            while (x < last && DottedDyadMeasureSeparator.fullInk(gray, width, x + 1, top, gap))
                x++;
            int thickness = x - begin + 1, center = (begin + x) / 2;
            if (thickness < 3
                    || thickness > gap * .40f
                    || !OmrMeasurePostProcessor.verticalRuleAt(
                            gray, width, center, top, bottom, gap)
                    || ClosedHeadBarlineGuard.attached(
                            gray, width, height, center, top, bottom, gap)
                    || DottedDyadMeasureSeparator.continuesBeyondStaff(
                            gray, width, height, center, top, bottom, gap)) continue;
            if (axis >= 0) return List.of();
            axis = center;
        }
        if (axis < 0) return List.of();
        int pad = Math.round(gap * .5f);
        var before =
                new MeasureRegion(
                        region.left(), (axis - pad) / (float) width, region.top(), region.bottom());
        var ending =
                new MeasureRegion(
                        (axis + pad) / (float) width,
                        region.right(),
                        region.top(),
                        region.bottom());
        var split = List.of(before, ending);
        var trial = OmrScoreInterpreter.analyze(labels, gray, width, height, split);
        if (!trial.rests().isEmpty()) return List.of();
        var cadence = trial.notes().stream().filter(n -> n.measureIndex() == 1).toList();
        if (cadence.size() != 1) return List.of();
        var finalNote = cadence.get(0);
        if (!plain(finalNote)
                || finalNote.tiedFromPrevious()
                || finalNote.beamCount() != 0
                || finalNote.unbeamedDurationBeats() != 2
                || finalNote.stemDirection() == 0
                || finalNote.augmentationDots() != 1
                || finalNote.positionInMeasure() < .20f
                || finalNote.positionInMeasure() > .65f) return List.of();
        var run =
                trial.notes().stream()
                        .filter(n -> n.measureIndex() == 0 && n.beamCount() == 2)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        if (run.size() != 12 || trial.notes().size() > 14) return List.of();
        float previous = left;
        for (int i = 0; i < run.size(); i++) {
            var note = run.get(i);
            float x = left + (before.right() * width - left) * note.positionInMeasure();
            if (!plain(note)
                    || note.unbeamedDurationBeats() != 0
                    || note.augmentationDots() != 0
                    || note.stemDirection() != finalNote.stemDirection()
                    || x - previous < gap * (i == 0 ? .7f : 1.6f)
                    || x - previous > gap * 3.9f) return List.of();
            previous = x;
        }
        return axis - previous >= gap * 1.15f && axis - previous <= gap * 2.9f ? split : List.of();
    }

    private static boolean plain(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && !n.crossStaffBeam()
                && n.tupletDivisor() == 1
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && n.boundaryTies() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }
}
