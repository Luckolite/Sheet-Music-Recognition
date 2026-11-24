// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;

/** Literal detached below-staff fingerings aligned to uniquely spaced, downward beamed heads. */
final class MeasuredStaffFingerings {
    private MeasuredStaffFingerings() {}

    private record Glyph(int digit, int left, int top, int right, int bottom, int shaftX) {
        boolean same(Glyph other) {
            return other != null && digit == other.digit
                    && Math.abs(left - other.left) <= 2 && Math.abs(right - other.right) <= 2
                    && Math.abs(top - other.top) <= 2 && Math.abs(bottom - other.bottom) <= 2
                    && Math.abs(shaftX - other.shaftX) <= 2;
        }
    }

    static List<PrintedFingeringDigits.Mark> retain(
            List<PrintedFingeringDigits.Mark> original, ScorePageInterpretation score,
            byte[] labels, byte[] raw, byte[] normalized, int width, int height) {
        long area = (long) width * height;
        if (labels == null || raw == null || normalized == null
                || labels.length != area || raw.length != area || normalized.length != area
                || score.notes().isEmpty() || score.notes().size() > 2048) return original;
        var notes = score.notes();
        if (notes.stream().noneMatch(note -> eligible(note) || ShaftMatchedFingerings.eligible(note, true) || DetachedFingeringThrees.eligible(note)))
            return original;
        var staffs = ScoreDynamicsDetector.alignStaffs(
                OmrScoreInterpreter.techniqueStaffs(labels, normalized, width, height, score.measures()),
                notes, height);
        if (staffs.isEmpty() || staffs.size() > 128) return original;
        var originalOwners = new HashSet<Integer>();
        for (var mark : original) originalOwners.add(mark.noteIndex());
        var heads = PrintedSlurContext.geometryHeads(notes, score.measures(), width, height);
        var ordinary = PrintedSlurFrames.prepare(normalized, width, height, staffs, notes, heads);
        var supplemental = PrintedSlurFrames.prepareSupplemental(
                normalized, width, height, staffs, notes, heads, ordinary, score.measures());
        var frames = new ArrayList<>(ordinary.frames());
        frames.addAll(supplemental.frames());
        var added = new ArrayList<PrintedFingeringDigits.Mark>();
        long[] remaining = {900_000};
        long[] measuredTipPixels = {150_000};
        long[] alternateThresholdPixels = {150_000};
        var measuredTipWork = new PrintedBeamShafts.Budget(1_200_000);
        long[] overlaidRemaining = {750_000};
        for (var frame : frames) {
            for (var head : frame.heads()) {
                var note = notes.get(head.owner());
                if (!eligible(note) || head.grace() || originalOwners.contains(head.owner())) continue;
                float[] local = frame.track().at(head.x());
                float floor = local[0], gap = local[1];
                if (gap < 10 || gap > 24 || ambiguous(frame.heads(), head, gap)) continue;
                int top = Math.round(head.y() + gap * 1.1f);
                int bottom = Math.round(floor + gap * 3.5f);
                if (top < 0 || bottom >= height || bottom <= top) continue;
                var norm = find(normalized, width, height, head, top, bottom, gap,
                        remaining, measuredTipPixels, alternateThresholdPixels, measuredTipWork);
                if (remaining[0] < 0) return original;
                int digit = -1;
                if (norm != null) {
                    var rawGlyph = find(raw, width, height, head, top, bottom, gap,
                        remaining, measuredTipPixels, alternateThresholdPixels, measuredTipWork);
                    if (remaining[0] < 0) return original;
                    if (norm.same(rawGlyph)) digit = norm.digit();
                }
                if (digit < 0 && overlaidRemaining[0] >= 0)
                    digit = RuleOverlaidFingerings.digit(raw, normalized, width, height,
                            head, frame.track(), floor, gap, overlaidRemaining);
                if (digit < 0 || !originalOwners.add(head.owner())) continue;
                added.add(new PrintedFingeringDigits.Mark(
                        head.owner(), digit, PrintedFingeringDigits.Placement.BELOW));
            }
        }
        added.addAll(ShaftMatchedFingerings.additional(original, added, notes, frames, raw, normalized, width, height, true));
        added.addAll(DetachedFingeringThrees.additional(original, added, notes, frames, raw, normalized, width, height));
        for (var mark : added) originalOwners.add(mark.noteIndex());
        long[] ruledThreeRemaining = {750_000};
        for (var frame : frames) {
            for (var head : frame.heads()) {
                if (ruledThreeRemaining[0] < 0) break;
                if (!eligible(notes.get(head.owner())) || head.grace() || originalOwners.contains(head.owner()))
                    continue;
                float[] local = frame.track().at(head.x());
                float floor = local[0], gap = local[1];
                if (gap < 10 || gap > 24 || ambiguous(frame.heads(), head, gap)) continue;
                if (!RuleOverlaidFingerings.three(raw, normalized, width, height,
                        head, frame.track(), floor, gap, ruledThreeRemaining)) continue;
                originalOwners.add(head.owner());
                added.add(new PrintedFingeringDigits.Mark(
                        head.owner(), 3, PrintedFingeringDigits.Placement.BELOW));
            }
        }
        added.addAll(ShaftMatchedFingerings.additional(
                original, added, notes, frames, raw, normalized, width, height, false));
        if (added.isEmpty()) return original;
        var result = new ArrayList<>(original);
        result.addAll(added);
        result.sort(java.util.Comparator.comparingInt(PrintedFingeringDigits.Mark::noteIndex));
        return List.copyOf(result);
    }

    private static boolean eligible(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.stemDirection() == -1 && note.beamCount() >= 1 && note.beamCount() <= 2 && !note.crossStaffBeam()
                && note.harmonicSemitones() == 0 && note.openStringTouch() == null;
    }

    private static boolean ambiguous(List<? extends PrintedSlurOwners.Location> heads,
            PrintedSlurOwners.Location owner, float gap) {
        for (var other : heads)
            if (other.owner() != owner.owner() && Math.abs(other.x() - owner.x()) < gap * .8f
                    && Math.abs(other.y() - owner.y()) < gap * 3f) return true;
        return false;
    }

    private static Glyph find(byte[] gray, int width, int height,
            PrintedSlurOwners.Location head, int top, int bottom, float gap, long[] remaining,
            long[] measuredTipPixels, long[] alternateThresholdPixels, PrintedBeamShafts.Budget measuredTipWork) {
        var shaft = PairedTremoloInk.stem(gray, width, height, head.x(), head.y(), gap, -1);
        if (shaft == null) return null;
        int left = Math.max(0, Math.round(shaft.x() - gap * .9f));
        int right = Math.min(width - 1, Math.round(shaft.x() + gap * .9f));
        int ordinaryTop = Math.max(top, Math.round(shaft.endY() + gap * .18f));
        var ordinary = readGlyph(gray, width, height, shaft.x(), left, right, ordinaryTop, bottom, gap, remaining, 165);
        if (ordinary != null || remaining[0] < 0) return ordinary;
        if (measuredTipPixels[0] >= 0) {
            int tip = physicalDownstemEnd(gray, width, height, head, shaft, gap, false, measuredTipWork);
            if (tip >= 0) {
                var measured = readGlyph(gray, width, height, shaft.x(), left, right,
                        Math.max(top, tip + 1), bottom, gap, measuredTipPixels, 165);
                if (measured != null) return measured;
            }
        }
        if (alternateThresholdPixels[0] < 0) return null;
        return readGlyph(gray, width, height, shaft.x(), left, right,
                ordinaryTop, bottom, gap, alternateThresholdPixels, 125);
    }

    // A nearby detached fingering can lengthen the permissive vertical trace.
    static int physicalDownstemEnd(byte[] gray, int width, int height, PrintedSlurOwners.Location head,
            PairedTremoloInk.Stem shaft, float gap, boolean acceptOrdinary, PrintedBeamShafts.Budget work) {
        boolean longTrace = shaft.endY() - head.y() >= gap * 4f;
        if (work.refused() || !acceptOrdinary && !longTrace) return -1;
        var shafts = PrintedBeamShafts.at(gray, width, height, head.x(), head.y(), gap, work);
        if (work.refused() || shafts.size() != 1 || shafts.get(0).direction() != 1) return -1;
        var physical = shafts.get(0);
        float excess = shaft.endY() - physical.endY();
        if (Math.abs(shaft.x() - (physical.left() + physical.right()) * .5f) > gap * .18f) return -1;
        boolean sameTip = acceptOrdinary && Math.abs(excess) <= gap * .18f;
        boolean extendedTrace = longTrace && excess >= gap * .6f && excess <= gap * 2.1f;
        if (!sameTip && !extendedTrace) return -1;
        return physical.endY();
    }

    private static Glyph readGlyph(byte[] gray, int width, int height, int shaftX,
            int left, int right, int top, int bottom, float gap, long[] remaining, int threshold) {
        long cells = (long) (right - left + 1) * (bottom - top + 1);
        if (top >= bottom || cells > 16_384) return null;
        remaining[0] -= cells;
        if (remaining[0] < 0) return null;
        var image = RawNumeralComponents.capture(gray, width, height,
                left, top, right, bottom, threshold, () -> {}, () -> {});
        Glyph match = null;
        for (var c : image.components()) {
            int h = c.bottom - c.top + 1, w = c.right - c.left + 1;
            if (c.left <= left || c.right >= right || c.top <= top || c.bottom >= bottom
                    || Math.abs((c.left + c.right) * .5f - shaftX) > gap * .42f
                    || h < gap * .80f || h > gap * 1.90f || w < gap * .39f || w > gap * 1.10f
                    || c.size() < w * h * .20f) continue;
            var literal = JoinedDigitTemplates.inspect(
                    gray, width, height, new BitSet(), c.left, c.top, c.right, c.bottom, () -> {});
            if (literal == null || literal.digit() < 1 || literal.digit() > 4) continue;
            if (match != null) return null;
            match = new Glyph(literal.digit(), c.left, c.top, c.right, c.bottom, Math.round(shaftX));
        }
        return match;
    }
}
