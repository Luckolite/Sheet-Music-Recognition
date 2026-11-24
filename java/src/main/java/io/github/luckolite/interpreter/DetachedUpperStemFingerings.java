// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;

/** Keeps detached numerals complete when a permissive stem trace follows their ink above a beam. */
final class DetachedUpperStemFingerings {
    private DetachedUpperStemFingerings() {}

    private record Box(int left, int top, int right, int bottom) {
        boolean same(Box other) {
            return other != null && Math.abs(left - other.left) <= 1 && Math.abs(right - other.right) <= 1
                    && Math.abs(top - other.top) <= 1 && Math.abs(bottom - other.bottom) <= 1;
        }
    }

    static PairedTremoloInk.Stem physical(byte[] image, int width, int height,
            PrintedSlurOwners.Location head, float gap) {
        var old = PairedTremoloInk.stem(image, width, height, head.x(), head.y(), gap, 1);
        var shafts = PrintedBeamShafts.at(image, width, height, head.x(), head.y(), gap,
                new PrintedBeamShafts.Budget(50_000));
        if (old == null || shafts.size() != 1 || shafts.get(0).direction() != -1) return null;
        var shaft = shafts.get(0);
        float separated = shaft.endY() - old.endY();
        if (separated < .6f * gap || separated > 2.0f * gap
                || Math.abs((shaft.left() + shaft.right()) * .5f - old.x()) > gap * .18f) return null;
        return new PairedTremoloInk.Stem((shaft.left() + shaft.right()) / 2, old.headY(), shaft.endY());
    }

    static List<PrintedFingeringDigits.Mark> ones(List<PrintedFingeringDigits.Mark> original,
            List<PrintedFingeringDigits.Mark> added, List<PrintedFingeringDigits.Mark> current,
            List<ScoreNoteEvent> notes,
            List<PrintedSlurPageCapture.Frame<PrintedSlurOwners.GeometryHead>> frames,
            byte[] raw, byte[] normalized, int width, int height) {
        var occupied = new HashSet<Integer>();
        for (var group : List.of(original, added, current))
            for (var mark : group) occupied.add(mark.noteIndex());
        var result = new ArrayList<PrintedFingeringDigits.Mark>();
        long[] budget = {500_000};
        for (var frame : frames) for (var head : frame.heads()) {
            if (occupied.contains(head.owner()) || head.grace()
                    || !ShaftMatchedFingerings.eligible(notes.get(head.owner()), true)) continue;
            float gap = frame.track().at(head.x())[1];
            if (gap < 10 || gap > 24 || frame.heads().stream().anyMatch(other -> other.owner() != head.owner()
                    && Math.abs(other.x() - head.x()) < gap * .8f
                    && Math.abs(other.y() - head.y()) < gap * 3f)) continue;
            var first = find(raw, width, height, head, gap, budget);
            if (budget[0] < 0) return List.of();
            if (first == null) continue;
            var second = find(normalized, width, height, head, gap, budget);
            if (budget[0] < 0) return List.of();
            if (first.same(second) && occupied.add(head.owner()))
                result.add(new PrintedFingeringDigits.Mark(head.owner(), 1, PrintedFingeringDigits.Placement.ABOVE));
        }
        return result;
    }

    private static Box find(byte[] image, int width, int height, PrintedSlurOwners.Location head,
            float gap, long[] budget) {
        var shaft = physical(image, width, height, head, gap);
        if (shaft == null) return null;
        int left = Math.round(shaft.x() - gap * .92f), right = Math.round(shaft.x() + gap * .92f);
        int bottom = shaft.endY() - 1, top = Math.round(bottom - gap * 2);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        budget[0] -= (long) (right - left + 1) * (bottom - top + 1);
        if (budget[0] < 0) return null;
        var graph = RawNumeralComponents.capture(image, width, height, left, top, right, bottom,
                165, () -> {}, () -> {});
        Box found = null;
        for (var glyph : graph.components()) {
            int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
            if (glyph.left <= left || glyph.right >= right || glyph.top <= top || glyph.bottom >= bottom
                    || h < gap * .8f || h > gap * 1.45f || w < gap * .39f || w > gap * 1.10f
                    || Math.abs((glyph.left + glyph.right) * .5f - shaft.x()) > gap * .3f) continue;
            var reading = JoinedDigitTemplates.inspect(image, width, height, new BitSet(),
                    glyph.left, glyph.top, glyph.right, glyph.bottom, () -> {});
            if (reading == null || reading.digit() != 1) continue;
            if (found != null) return null;
            found = new Box(glyph.left, glyph.top, glyph.right, glyph.bottom);
        }
        return found;
    }
}
