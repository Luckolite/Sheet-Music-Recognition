// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;

/** Recovers detached fingerings beyond a stem tip using independently retained same-page numeral shapes. */
final class ShaftMatchedFingerings {
    private static final int[] ORDINARY_LEVELS = {165, 125};
    private static final int[] RECOVERED_UPPER_LEVELS = {80, 105, 135, 165};

    private ShaftMatchedFingerings() {}

    private record Box(int left, int top, int right, int bottom) {
        boolean same(Box other) {
            return other != null && Math.abs(left - other.left) <= 2
                    && Math.abs(top - other.top) <= 2 && Math.abs(right - other.right) <= 2
                    && Math.abs(bottom - other.bottom) <= 2;
        }
        boolean intersects(Box other) {
            return left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top;
        }
    }

    private record Reference(Box box, BitSet raw, BitSet normalized, float[] rawShape, float[] normalizedShape) {}

    static boolean eligible(ScoreNoteEvent note, boolean above) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.stemDirection() == (above ? 1 : -1) && note.beamCount() >= 1 && note.beamCount() <= 2
                && !note.crossStaffBeam() && note.harmonicSemitones() == 0 && note.openStringTouch() == null;
    }

    static List<PrintedFingeringDigits.Mark> additional(
            List<PrintedFingeringDigits.Mark> original, List<PrintedFingeringDigits.Mark> added,
            List<ScoreNoteEvent> notes,
            List<PrintedSlurPageCapture.Frame<PrintedSlurOwners.GeometryHead>> frames,
            byte[] raw, byte[] normalized, int width, int height, boolean above) {
        var alreadyMarked = new HashSet<Integer>();
        for (var mark : added) alreadyMarked.add(mark.noteIndex());
        var twoOwners = new HashSet<Integer>();
        for (var mark : original) {
            alreadyMarked.add(mark.noteIndex());
            if (mark.placement() == PrintedFingeringDigits.Placement.ABOVE && mark.digit() == 2)
                twoOwners.add(mark.noteIndex());
        }
        if (twoOwners.size() < 2 || twoOwners.size() > 64) return List.of();
        var references = new ArrayList<Reference>();
        for (var frame : frames) for (var head : frame.heads()) {
            if (!twoOwners.contains(head.owner())) continue;
            float gap = frame.track().at(head.x())[1];
            if (gap < 10 || gap > 24) continue;
            var a = reference(raw, width, height, head, gap);
            var b = reference(normalized, width, height, head, gap);
            if (a == null || !a.same(b) || references.stream().anyMatch(ref -> ref.box().intersects(a)))
                continue;
            var rawBits = bits(raw, width, a);
            var normalizedBits = bits(normalized, width, b);
            if (similarity(rawBits, normalizedBits) >= .90f)
                references.add(new Reference(a, rawBits, normalizedBits,
                        MatchedDetachedThreeShape.vector(raw, width, a.left(), a.top(), a.right(), a.bottom()),
                        MatchedDetachedThreeShape.vector(normalized, width, b.left(), b.top(), b.right(), b.bottom())));
        }
        if (references.size() < 2) return List.of();

        var recovered = new ArrayList<PrintedFingeringDigits.Mark>();
        var boxes = new ArrayList<Box>();
        var rawTwos = new ArrayList<float[]>();
        var normalizedTwos = new ArrayList<float[]>();
        if (above) for (var reference : references) {
            rawTwos.add(reference.rawShape());
            normalizedTwos.add(reference.normalizedShape());
        }
        long[] remaining = {750_000};
        long[] lighterThresholdBudget = {250_000};
        for (var frame : frames) for (var head : frame.heads()) {
            if (alreadyMarked.contains(head.owner()) || head.grace() || !eligible(notes.get(head.owner()), above))
                continue;
            float[] local = frame.track().at(head.x());
            float gap = local[1];
            if (gap < 10 || gap > 24 || adjacentHead(frame.heads(), head, gap)) continue;
            int top = Math.round(above ? local[0] - gap * 7.5f : head.y() + gap * 1.1f);
            int bottom = Math.round(above ? head.y() - gap * 1.1f : local[0] + gap * 3.5f);
            if (top < 0 || bottom >= height || top >= bottom) continue;
            var first = find(raw, width, height, head, gap, top, bottom, remaining, lighterThresholdBudget, references, false, above);
            if (remaining[0] < 0) return List.of();
            if (first == null) continue;
            var second = find(normalized, width, height, head, gap, top, bottom, remaining, lighterThresholdBudget, references, true, above);
            if (remaining[0] < 0) return List.of();
            if (!first.same(second) || references.stream().anyMatch(ref -> ref.box().intersects(first))
                    || boxes.stream().anyMatch(box -> box.intersects(first))) continue;
            boxes.add(first);
            if (above && rawTwos.size() < 64) {
                // Reuse detached matches established before the staff-rule pass.
                rawTwos.add(MatchedDetachedThreeShape.vector(raw, width,
                        first.left(), first.top(), first.right(), first.bottom()));
                normalizedTwos.add(MatchedDetachedThreeShape.vector(normalized, width,
                        second.left(), second.top(), second.right(), second.bottom()));
            }
            var placement = above ? PrintedFingeringDigits.Placement.ABOVE : PrintedFingeringDigits.Placement.BELOW;
            recovered.add(new PrintedFingeringDigits.Mark(head.owner(), 2, placement));
        }
        if (above) {
            recovered.addAll(DetachedUpperStemFingerings.ones(
                    original, added, recovered, notes, frames, raw, normalized, width, height));
            for (var mark : recovered) alreadyMarked.add(mark.noteIndex());
            long[] rulePixels = {750_000};
            for (var frame : frames) for (var head : frame.heads()) {
                if (rulePixels[0] < 0) return recovered;
                if (alreadyMarked.contains(head.owner()) || head.grace() || !eligible(notes.get(head.owner()), true))
                    continue;
                float[] local = frame.track().at(head.x());
                float gap = local[1];
                if (gap < 10 || gap > 24 || adjacentHead(frame.heads(), head, gap)) continue;
                int digit = RuleOverlaidFingerings.above(raw, normalized, width, height, head, frame.track(),
                        local[0], gap, rulePixels, rawTwos, normalizedTwos);
                if (digit < 0) continue;
                alreadyMarked.add(head.owner());
                recovered.add(new PrintedFingeringDigits.Mark(head.owner(), digit, PrintedFingeringDigits.Placement.ABOVE));
            }
        }
        return recovered;
    }

    private static boolean adjacentHead(List<PrintedSlurOwners.GeometryHead> heads,
            PrintedSlurOwners.Location head, float gap) {
        return heads.stream().anyMatch(other -> other.owner() != head.owner()
                && Math.abs(other.x() - head.x()) < gap * .8f
                && Math.abs(other.y() - head.y()) < gap * 3f);
    }

    private static Box find(byte[] image, int width, int height, PrintedSlurOwners.Location head,
            float gap, int top, int bottom, long[] budget, long[] lighterThresholdBudget,
            List<Reference> references, boolean normalized, boolean above) {
        var shaft = PairedTremoloInk.stem(image, width, height, head.x(), head.y(), gap, above ? 1 : -1);
        if (shaft == null) return null;
        boolean recoveredTip = false;
        if (above) {
            var physical = DetachedUpperStemFingerings.physical(image, width, height, head, gap);
            if (physical != null) {
                shaft = physical;
                recoveredTip = true;
            }
        }
        int left = Math.max(0, Math.round(shaft.x() - gap * .9f));
        int right = Math.min(width - 1, Math.round(shaft.x() + gap * .9f));
        if (above) bottom = Math.min(bottom, recoveredTip ? shaft.endY() - 1
                : Math.round(shaft.endY() - gap * .18f));
        else top = Math.max(top, Math.round(shaft.endY() + gap * .18f));
        long area = (long) (right - left + 1) * (bottom - top + 1);
        if (top >= bottom || area > 16_384) return null;
        Box result = null;
        int[] levels = recoveredTip ? RECOVERED_UPPER_LEVELS : ORDINARY_LEVELS;
        for (int level : levels) {
            if (level == 125) {
                if (result != null || lighterThresholdBudget[0] < area) break;
                lighterThresholdBudget[0] -= area;
            } else {
                budget[0] -= area;
                if (budget[0] < 0) return null;
            }
            var graph = RawNumeralComponents.capture(image, width, height, left, top, right, bottom,
                    level, () -> {}, () -> {});
            for (var c : graph.components()) {
                int w = c.right - c.left + 1, h = c.bottom - c.top + 1;
                if (c.left <= left || c.right >= right || c.top <= top || c.bottom >= bottom
                        || Math.abs((c.left + c.right) * .5f - shaft.x()) > gap * .42f
                        || h < gap * .8f || h > gap * 1.90f || w < gap * .39f || w > gap * 1.10f
                        || c.size() < w * h * .20f) continue;
                var box = new Box(c.left, c.top, c.right, c.bottom);
                var mask = bits(image, width, box);
                int matches = 0;
                float best = 0;
                for (var reference : references) {
                    float score = similarity(mask, normalized ? reference.normalized() : reference.raw());
                    if (score >= .74f) matches++;
                    best = Math.max(best, score);
                }
                if ((matches < 2 || best < .78f) && !matchesOwnedTwos(image, width, box, references, normalized)) continue;
                var format = JoinedDigitTemplates.inspect(image, width, height, new BitSet(),
                        c.left, c.top, c.right, c.bottom, () -> {});
                if (format == null || format.digit() >= 0 && format.digit() != 2
                        || format.scores()[2] < .52f) continue;
                if (result != null && !result.same(box)) return null;
                result = box;
            }
        }
        return result;
    }

    private static boolean matchesOwnedTwos(byte[] gray, int width, Box box,
            List<Reference> references, boolean normalized) {
        float[] shape = MatchedDetachedThreeShape.vector(gray, width, box.left(), box.top(), box.right(), box.bottom());
        float required = Math.max(.84f, JoinedDigitTemplates.maxEightSimilarity(shape) + .12f);
        int matches = 0;
        for (var reference : references) {
            float[] sample = normalized ? reference.normalizedShape() : reference.rawShape();
            float dot = 0;
            for (int i = 0; i < shape.length; i++) dot += shape[i] * sample[i];
            if (dot >= required && ++matches == 2) return true;
        }
        return false;
    }

    private static Box reference(byte[] gray, int width, int height,
            PrintedSlurOwners.Location head, float gap) {
        int left = Math.round(head.x() - .83f * gap), right = Math.round(head.x() + .83f * gap);
        int top = Math.round(head.y() - 2.5f * gap), bottom = Math.round(head.y() - .70f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var graph = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom,
                165, () -> {}, () -> {});
        Box result = null;
        for (var c : graph.components()) {
            int w = c.right - c.left + 1, h = c.bottom - c.top + 1;
            if (c.left <= left || c.right >= right || c.top <= top || c.bottom >= bottom
                    || h < gap * .78f || h > gap * 1.4f || w < gap * .45f || w > gap * 1.12f) continue;
            if (result != null) return null;
            result = new Box(c.left, c.top, c.right, c.bottom);
        }
        return result;
    }

    private static float similarity(BitSet a, BitSet b) {
        var shared = (BitSet) a.clone();
        shared.and(b);
        int common = shared.cardinality();
        return common / (float) Math.max(1, a.cardinality() + b.cardinality() - common);
    }

    private static BitSet bits(byte[] gray, int width, Box box) {
        var result = new BitSet(640);
        int w = box.right() - box.left() + 1, h = box.bottom() - box.top() + 1;
        for (int y = 0; y < 32; y++) for (int x = 0; x < 20; x++) {
            int xx = box.left() + (2 * x + 1) * w / 40;
            int yy = box.top() + (2 * y + 1) * h / 64;
            if ((gray[yy * width + xx] & 255) <= 165) result.set(y * 20 + x);
        }
        return result;
    }
}
