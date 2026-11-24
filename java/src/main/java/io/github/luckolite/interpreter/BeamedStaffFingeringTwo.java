// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.BitSet;

/** A printed 2 on a staff rule can share the shaft end of a high downstem note. */
final class BeamedStaffFingeringTwo {
    private BeamedStaffFingeringTwo() {}

    static boolean owns(ScoreNoteEvent note, MeasureRegion bar,
            byte[] gray, byte[] raw, int width, int height,
            float l, float t, float r, float b) {
        if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                || note.staffIndex() != 0 || note.staffCount() != 1 || note.crossStaffBeam()
                || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || note.stemDirection() != -1 || note.beamCount() != 1 || note.staffStep() < 10
                || note.harmonicSemitones() != 0 || note.openStringTouch() != null
                || (note.articulations() & NoteOrnament.GRACE) != 0) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float x = (l + r) * .5f;
        float headX = (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width;
        float headY = note.pageY() * height;
        float rise = (t + b) * .5f - headY;
        if (gap < 10 || gap > 25 || l < gap || r >= width - gap
                || l < bar.left() * width || r > bar.right() * width
                || t < gap || b >= height - gap
                || r - l < gap || r - l > 1.8f * gap || b - t < 1.3f * gap || b - t > 2.15f * gap
                || Math.abs(x - headX) > .33f * gap || rise < 4.8f * gap || rise > 7.5f * gap)
            return false;
        return proves(gray, width, height, note.staffStep(), headX, headY, l, t, r, b, gap)
                && proves(raw, width, height, note.staffStep(), headX, headY, l, t, r, b, gap);
    }

    private static boolean proves(byte[] plane, int width, int height, int step,
            float headX, float headY, float l, float t, float r, float b, float nominal) {
        float x = (l + r) * .5f;
        var staff = PrintedDirectionStaff.printedRules(
                plane, width, height, x, headY + step * nominal * .5f, nominal);
        if (staff == null) return false;
        float gap = staff[1], rule = staff[0] - 2 * gap;
        if (rule - t < .38f * gap || rule - t > .75f * gap
                || b - rule < .90f * gap || b - rule > 1.30f * gap) return false;
        var shaft = PairedTremoloInk.stem(plane, width, height, headX, headY, gap, -1);
        if (shaft == null || x - shaft.x() < .50f * gap || x - shaft.x() > .75f * gap
                || Math.abs(shaft.endY() - rule) > .21f * gap) return false;

        int left = Math.round(x - .52f * gap), right = Math.round(x + .34f * gap);
        int top = Math.round(rule - .15f * gap), bottom = Math.round(rule + .80f * gap);
        int outside = Math.round(.48f * gap);
        var rows = new BitSet();
        int links = 0;
        for (int y = top; y <= Math.round(rule + .15f * gap); y++) {
            int offset = y * width;
            if (darkRun(plane, offset, Math.round(l) - outside, Math.round(l) - 1) < outside - 1
                    || darkRun(plane, offset, Math.round(r) + 1, Math.round(r) + outside) < outside - 1)
                continue;
            rows.set(offset + left, offset + right + 1);
            links++;
        }
        if (links < 2 || links > .26f * gap || shaft.x() >= left) return false;
        int cap = 0;
        for (int y = Math.round(rule - .30f * gap); y < top + 1; y++) {
            if (darkRun(plane, y * width, Math.round(x - .22f * gap), Math.round(x + .22f * gap))
                    >= .25f * gap) cap++;
        }
        if (cap < 2) return false;
        var glyph = JoinedDigitTemplates.inspect(
                plane, width, height, rows, left, top, right, bottom, () -> {});
        return glyph != null && glyph.digit() == 2
                && glyph.best() >= .69f && glyph.best() - glyph.runner() >= .12f;
    }

    private static int darkRun(byte[] plane, int offset, int lo, int hi) {
        int ink = 0;
        for (int x = lo; x <= hi; x++) {
            if ((plane[offset + x] & 255) <= 165) ink++;
        }
        return ink;
    }
}
