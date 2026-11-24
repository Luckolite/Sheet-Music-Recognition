// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Identifies a bottom-rule fingering that has been retained as a stemless attack. */
final class RuledTripletFingeringAlias {
    private RuledTripletFingeringAlias() {}

    static boolean proves(byte[] raw, byte[] normalized, int width, int height,
            MeasureRegion bar, ScoreNoteEvent alias, ScoreNoteEvent terminal) {
        if (normalized == null || normalized.length != raw.length || width < 1 || height < 1
                || raw.length != (long) width * height
                || alias.staffStep() < -2 || alias.staffStep() > 0 || alias.stemDirection() != 0
                || alias.articulations() != 0 || alias.kind() != ScoreNoteEvent.Kind.PITCHED
                || alias.staffIndex() != 0 || alias.staffCount() != 1
                || alias.visualSize() != ScoreNoteEvent.VisualSize.FULL || alias.beamCount() != 1
                || alias.writtenAccidental() != 2 || alias.augmentationDots() != 0
                || alias.unbeamedDurationBeats() != 0 || alias.tupletDivisor() != 1
                || alias.tiedFromPrevious() || alias.boundaryTies() != 0 || alias.crossStaffBeam()
                || alias.leadingRestBeats() != 0 || alias.followingRestBeats() != 0
                || alias.harmonicSemitones() != 0 || alias.openStringTouch() != null
                || alias.clefBottomDiatonic() != terminal.clefBottomDiatonic()
                || terminal.staffStep() < 9 || terminal.stemDirection() != -1
                || terminal.beamCount() != 2) return false;
        float x = pixelX(bar, alias, width), terminalX = pixelX(bar, terminal, width);
        float y = alias.pageY() * height, terminalY = terminal.pageY() * height;
        var original = find(raw, width, height, bar, x, y, terminalX, terminalY);
        return original != null && original.equals(find(normalized, width, height, bar, x, y, terminalX, terminalY));
    }

    /** The removed alias proves the literal. The final score supplies its unique owner. */
    static List<PrintedFingeringDigits.Mark> attach(
            List<PrintedFingeringDigits.Mark> existing, List<ScoreNoteEvent> proved,
            List<ScoreNoteEvent> retained) {
        if (proved.isEmpty()) return existing;
        var owners = new HashMap<NoteLocation, Integer>();
        for (int i = 0; i < retained.size(); i++) {
            var note = retained.get(i);
            if (note.kind() == ScoreNoteEvent.Kind.PITCHED
                    && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                    && (note.articulations() & NoteOrnament.GRACE) == 0)
                owners.merge(new NoteLocation(note), i, (a, b) -> -1);
        }
        var assigned = new BitSet(retained.size());
        for (var mark : existing) assigned.set(mark.noteIndex());
        var result = new ArrayList<>(existing);
        for (var terminal : proved) {
            int index = owners.getOrDefault(new NoteLocation(terminal), -1);
            if (index < 0 || assigned.get(index)) continue;
            assigned.set(index);
            result.add(new PrintedFingeringDigits.Mark(index, 3, PrintedFingeringDigits.Placement.BELOW));
        }
        if (result.size() == existing.size()) return existing;
        result.sort(Comparator.comparingInt(PrintedFingeringDigits.Mark::noteIndex));
        return List.copyOf(result);
    }

    private record NoteLocation(int measure, int staff, int staffCount, float position, float pageY,
            int staffStep, int clef) {
        NoteLocation(ScoreNoteEvent note) {
            this(note.measureIndex(), note.staffIndex(), note.staffCount(), note.positionInMeasure(),
                    note.pageY(), note.staffStep(), note.clefBottomDiatonic());
        }
    }

    private static Glyph find(byte[] raster, int width, int height, MeasureRegion bar,
            float x, float y, float terminalX, float terminalY) {
        var track = WrappedMeasureBoundaries.closedTripletStaff(raster, width, height, bar);
        if (track == null) return null;
        var at = track.at(x);
        float floor = at[0], gap = at[1];
        if (gap < 10 || gap > 25
                || Math.abs(x - terminalX) > gap * .43f
                || y < floor + gap * .12f || y > floor + gap * .64f
                || terminalY > floor - gap * 4.0f) return null;
        int left = Math.round(x - gap * .78f), right = Math.round(x + gap * .78f);
        int top = Math.round(floor - gap * .65f), bottom = Math.round(floor + gap * 1.0f);
        int flank = Math.round(gap * .62f);
        if (left - flank < 0 || right + flank >= width || top < 0 || bottom >= height) return null;
        // At a line end the system track may have no verified sample at the glyph.
        // Independently measure the next staff rule on both sides of the digit as well.
        int reference = Math.round(floor - gap);
        boolean staffNeighbor = false;
        for (int row = reference - 2; row <= reference + 2; row++) {
            if (row < 0) return null;
            int before = count(raster, width, row, left - flank, left - 1);
            int after = count(raster, width, row, right + 1, right + flank);
            staffNeighbor |= Math.min(before, after) * 5 >= flank * 4;
        }
        if (!staffNeighbor) return null;
        var rule = new BitSet();
        int low = right, high = left, begin = bottom, end = top, mass = 0;
        for (int row = top; row <= bottom; row++) {
            int before = count(raster, width, row, left - flank, left - 1);
            int after = count(raster, width, row, right + 1, right + flank);
            if (Math.abs(row - floor) <= gap * .22f && Math.min(before, after) * 5 >= flank * 4) {
                rule.set(row);
                continue;
            }
            if (before > 1 || after > 1 || count(raster, width, row, left, left) != 0
                    || count(raster, width, row, right, right) != 0) return null;
            for (int col = left + 1; col < right; col++) {
                if (!dark(raster, width, col, row)) continue;
                low = Math.min(low, col);
                high = Math.max(high, col);
                begin = Math.min(begin, row);
                end = Math.max(end, row);
                mass++;
            }
        }
        int w = high - low + 1, h = end - begin + 1;
        if (rule.cardinality() < 2 || rule.cardinality() > gap * .30f
                || rule.nextSetBit(0) <= begin || rule.length() - 1 >= end
                || begin <= top || end >= bottom || w < gap * .50f || w > gap * 1.1f
                || h < gap * .85f || h > gap * 1.35f || mass < w * h * .30f
                || x < low || x > high || y < begin || y > end) return null;
        var mask = new BitSet();
        for (int row : rule.stream().toArray()) mask.set(row * width + low, row * width + high + 1);
        var fit = JoinedDigitTemplates.inspect(raster, width, height, mask, low, begin, high, end, () -> {});
        if (fit == null || fit.digit() != 3
                && !RuledFingeringThree.proves(raster, width, mask, low, begin, high, end, fit)
                && !provesLowerLobe(raster, width, low, begin, high, end, fit)) return null;
        return new Glyph(low, begin, high, end, rule.stream().boxed().toList());
    }

    /** The staff line may hide the upper opening. Prove a detached lower left tip and
     * an open paper path above its counter, together with the existing digit fit. */
    private static boolean provesLowerLobe(byte[] raster, int width, int left, int top, int right,
            int bottom, JoinedDigitTemplates.Reading fit) {
        if (fit.digit() >= 0 || fit.scores()[3] < .65f || fit.scores()[3] < fit.best() - .06f)
            return false;
        int w = right - left, h = bottom - top;
        int center = left + Math.round(w * .48f);
        int leftTip = left + Math.round(w * .22f);
        int rightTip = left + Math.round(w * .72f);
        int tipStart = top + Math.round(h * .70f), tipEnd = top + Math.round(h * .84f);
        int opening = tipStart - Math.max(2, Math.round(h * .12f));
        if (count(raster, width, opening, left, leftTip) != 0
                || !dark(raster, width, rightTip, opening)) return false;
        for (int row = opening + 1; row <= tipEnd; row++) {
            if (dark(raster, width, center, row) || !dark(raster, width, rightTip, row)) return false;
            if (row >= tipStart && !dark(raster, width, leftTip, row)) return false;
        }
        int bridge = bottom - Math.round(h * .05f);
        return count(raster, width, bridge, leftTip, rightTip) == rightTip - leftTip + 1;
    }

    private static float pixelX(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width;
    }

    private static int count(byte[] raster, int width, int row, int left, int right) {
        int count = 0;
        for (int col = left; col <= right; col++) if (dark(raster, width, col, row)) count++;
        return count;
    }

    private static boolean dark(byte[] raster, int width, int x, int y) {
        return (raster[y * width + x] & 255) < 165;
    }

    private record Glyph(int left, int top, int right, int bottom, List<Integer> ruleRows) {}
}
