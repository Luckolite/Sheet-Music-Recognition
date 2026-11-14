// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds a separately engraved short harmonic cadence after a complete written bar. */
final class SystemQuarterCadence {
    private SystemQuarterCadence() {}

    static List<MeasureRegion> recover(
            List<MeasureRegion> regions, byte[] labels, byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height) return regions;
        var result = new ArrayList<MeasureRegion>();
        boolean changed = false;
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            float bottom = region.bottom() * height, top = region.top() * height;
            float gap = (bottom - top) / 8.2f;
            boolean lastInRow = i + 1 < regions.size()
                    && Math.abs(regions.get(i + 1).top() * height - top) > gap * 5;
            var split = lastInRow ? prove(region, labels, gray, width, height) : List.<MeasureRegion>of();
            if (split.isEmpty()) result.add(region);
            else {
                result.addAll(split);
                changed = true;
            }
        }
        return changed ? List.copyOf(result) : regions;
    }

    static List<MeasureRegion> prove(
            MeasureRegion region, byte[] labels, byte[] gray, int width, int height) {
        if (region.cadenceQuarterBeats() != 0) return List.of();
        float top = region.top() * height, bottom = region.bottom() * height;
        float gap = (bottom - top) / 8.2f;
        float left = region.left() * width, right = region.right() * width;
        if (gap < 6 || gap > 32 || right - left < gap * 32 || right - left > gap * 45)
            return List.of();
        float hint = bottom - 2.1f * gap;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, right - gap * 2.5f, hint, gap);
        if (rails == null) rails = PrintedDirectionStaff.printedRules(
                gray, width, height, right - gap * 4, hint, gap);
        if (rails == null) return List.of();
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - rails[1] * 4, rails[0], rails[1], 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, true) < 0)
            return List.of();

        var single = List.of(region);
        var raw = OmrScoreInterpreter.analyze(labels, gray, width, height, single);
        if (!raw.rests().isEmpty() || raw.notes().size() < 17 || raw.notes().size() > 34) return List.of();
        var owners = OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, single);
        var notes = ArtificialHarmonics.apply(labels, gray, width, height, single, raw.notes(), owners);
        if (notes.size() != 17) return List.of();
        notes = notes.stream().sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        float[] x = new float[notes.size()], y = new float[notes.size()];
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1 || n.staffIndex() != 0
                    || n.octaveShift() != 2 || n.tupletDivisor() != 1 || n.augmentationDots() != 0
                    || n.crossStaffBeam() || (n.boundaryTies() & (i == 0 ? 12 : 15)) != 0
                    || n.stemDirection() == 0
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0)
                return List.of();
            int beams = i == 16 ? 0 : 2;
            float duration = i == 16 ? 1 : 0;
            if (n.beamCount() != beams || n.unbeamedDurationBeats() != duration) return List.of();
            x[i] = left + n.positionInMeasure() * (right - left);
            y[i] = n.pageY() * height;
            if (i == 0 || i == 16) continue;
            if (x[i] - x[i - 1] < gap * 1.3f || x[i] - x[i - 1] > gap * 3.5f) return List.of();
            var p = notes.get(i - 1);
            boolean linked = p.stemDirection() == n.stemDirection()
                    && PrintedTupletBeamOwner.connectedHeads(
                       gray, width, height, x[i - 1], y[i - 1], x[i], y[i], gap, n.stemDirection());
            if (linked != (i % 4 != 0)) return List.of();
        }

        // The separator can only occur in the printed inter-note gap, outside both shafts.
        int start = Math.round(x[15] + gap * 1.35f);
        int end = Math.round(x[16] - gap * 1.05f);
        int first = -1, last = -1;
        for (int column = start; column <= end; column++) {
            if (!crossesSpaces(gray, width, staff, column)) continue;
            if (last >= 0 && column > last + 1) return List.of();
            if (first < 0) first = column;
            last = column;
        }
        if (first < 0 || last - first > gap * .35f) return List.of();
        int cut = (first + last) / 2;
        if (right - cut < gap * 4 || right - cut > gap * 9
                || x[16] - cut < gap * 1.2f || x[16] - cut > gap * 3.5f
                || ClosedHeadBarlineGuard.attached(gray, width, height, cut, Math.round(staff.top()),
                                                    Math.round(staff.bottom()), staff.gap())) return List.of();
        int margin = Math.max(2, Math.round(gap * .5f));
        return List.of(new MeasureRegion(region.left(), (float) (cut - margin) / width,
                                         region.top(), region.bottom()),
                       new MeasureRegion((float) (cut + margin) / width, region.right(),
                                         region.top(), region.bottom(), 1));
    }

    private static boolean crossesSpaces(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff, int x) {
        for (int i = 0; i < 4; i++) {
            int a = Math.round(staff.top() + (i + .2f) * staff.gap());
            int b = Math.round(staff.top() + (i + .8f) * staff.gap());
            int dark = 0;
            for (int y = a; y <= b; y++)
                if ((gray[y * width + x] & 255) < 112) dark++;
            if (dark < (b - a + 1) * .8f) return false;
        }
        return true;
    }
}
