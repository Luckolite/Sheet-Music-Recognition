// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Proves the accidental ink around a complete, isolated, printed quarter dyad. */
final class PrintedQuarterDyadAccidentals {
    private PrintedQuarterDyadAccidentals() {}

    static boolean proves(byte[] labels, byte[] gray, int width, int height,
            MeasureRegion measure, MeasureRegion next, List<ScoreNoteEvent> chord,
            List<PlayingTechniqueDetector.Staff> staffs) {
        if (gray == null || labels == null || gray.length != (long) width * height
                || labels.length != gray.length || chord.size() != 2 || next == null
                || next.top() <= measure.bottom() || measure.cadenceQuarterBeats() != 0) return false;
        var a = chord.get(0);
        var b = chord.get(1);
        if (a.unbeamedDurationBeats() != 1 || b.unbeamedDurationBeats() != 1
                || a.stemDirection() == 0 || a.stemDirection() != b.stemDirection()
                || a.staffStep() == b.staffStep()) return false;
        if (b.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL) {
            a = chord.get(1);
            b = chord.get(0);
        }
        if (a.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                || b.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                        && b.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return false;

        float left = measure.left() * width, right = measure.right() * width;
        float span = right - left;
        float ax = left + span * a.positionInMeasure(), bx = left + span * b.positionInMeasure();
        float ay = a.pageY() * height, by = b.pageY() * height;
        if (a.positionInMeasure() < .25f || a.positionInMeasure() > .68f) return false;
        for (var fitted : staffs) {
            float gap = fitted.gap();
            if (gap < 6 || gap > 32 || fitted.index() != 0 || fitted.count() != 1
                    || span > gap * 8.5f || Math.abs(ax - bx) > gap * .30f
                    || Math.abs(ay - by) < gap * .7f
                    || fitted.top() < measure.top() * height - gap
                    || fitted.bottom() > measure.bottom() * height + gap) continue;
            var rules = PrintedDirectionStaff.printedRules(
                    gray, width, height, left + span * .38f, fitted.bottom(), gap);
            if (rules == null) continue;
            gap = rules[1];
            float top = rules[0] - 4 * gap;
            if (top < 0 || rules[0] >= height) continue;
            var staff = new PlayingTechniqueDetector.Staff(top, rules[0], gap, 0, 1);
            int opening = PrintedCadenceDurations.boundaryColumn(
                    gray, width, staff, left - gap * .5f, true, false);
            int closing = PrintedCadenceDurations.boundaryColumn(
                    gray, width, staff, right, false, false);
            if (closing < 0) closing = PrintedCadenceDurations.boundaryColumn(
                    gray, width, staff, right + gap * .35f, false, false);
            if (opening < 0 || closing < 0 || ax - opening < 2.5f * gap
                    || closing - ax < 2.5f * gap) continue;
            if (ownedPair(labels, gray, width, height, staff, opening, closing, ax, ay, bx, by))
                return true;
        }
        return false;
    }

    private static boolean ownedPair(byte[] labels, byte[] gray, int width, int height,
            PlayingTechniqueDetector.Staff staff, int opening, int closing,
            float ax, float ay, float bx, float by) {
        float gap = staff.gap();
        int left = Math.max(opening + Math.round(gap * .48f), Math.round(Math.min(ax, bx) - gap * 3.15f));
        int right = Math.round(Math.max(ax, bx) - gap * .7f);
        int top = Math.max(0, Math.round(Math.min(ay, by) - gap * 2.15f));
        int bottom = Math.min(height - 1, Math.round(Math.max(ay, by) + gap * 1.95f));
        if (right <= left || bottom <= top) return false;
        int w = right - left + 1, h = bottom - top + 1;
        byte[] semantic = new byte[w * h];
        Arrays.fill(semantic, (byte) 255);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = (top + y) * width + left + x;
                if (labels[at] == OmrMeasurePostProcessor.CLEF_OR_KEY && (gray[at] & 255) < 170)
                    semantic[y * w + x] = 0;
            }
        }
        var naturals = new ArrayList<Ink>();
        int naturalRight = Math.min(w - 1, Math.round(ax - left - gap * .87f));
        for (int x = Math.max(0, Math.round(ax - left - gap * 2.65f));
                x < naturalRight; x++) {
            for (int r = Math.max(x + Math.round(gap * .55f), Math.round(ax - left - gap * 1.5f));
                    r <= Math.min(naturalRight, x + Math.round(gap * 1.06f)); r++) {
                if (!OmrScoreInterpreter.provesNaturalMeterInk(
                        semantic, w, h, x, r, ay - top, gap, Math.round(staff.top()) - top)) continue;
                naturals.add(new Ink(x, r, Math.max(0, Math.round(ay - top - gap * 1.85f)),
                        Math.min(h - 1, Math.round(ay - top + gap * 1.85f))));
            }
        }
        if (naturals.isEmpty()) return false;

        var flats = new ArrayList<Ink>();
        for (int x = Math.max(0, Math.round(bx - left - gap * 3.10f));
                x < Math.min(w - 1, Math.round(bx - left - gap * 1.27f)); x++) {
            for (int r = x + Math.round(gap * .56f);
                    r <= Math.min(w - 1, x + Math.round(gap * 1.10f)); r++) {
                var flat = flatGlyph(semantic, w, h, x, r, by - top, gap);
                if (flat != null) flats.add(flat);
            }
        }
        for (var natural : naturals) {
            for (var flat : flats) {
                int yOverlap = Math.min(natural.bottom, flat.bottom) - Math.max(natural.top, flat.top);
                int xOverlap = Math.min(natural.right, flat.right) - Math.max(natural.left, flat.left);
                if (yOverlap > gap * .6f && xOverlap > gap * .24f) continue;
                if (emptyExceptOwned(gray, width, staff, opening, closing,
                        ax, bx, left, top, semantic, w, natural, flat)) return true;
            }
        }
        return false;
    }

    private static Ink flatGlyph(byte[] semantic, int w, int h, int left, int right,
            float center, float gap) {
        int top = Math.max(0, Math.round(center - gap * 2.15f));
        int bottom = Math.min(h - 1, Math.round(center + gap * .75f));
        int spine = -1, maxRows = 0;
        for (int x = left; x <= left + (right - left) * .39f; x++) {
            int rows = 0;
            for (int y = top; y <= bottom; y++) if (semantic[y * w + x] == 0) rows++;
            if (rows > maxRows) { maxRows = rows; spine = x; }
        }
        if (maxRows < gap * 1.4f) return null;
        int y0 = bottom, y1 = top;
        for (int y = top; y <= bottom; y++) {
            if (semantic[y * w + spine] != 0) continue;
            y0 = Math.min(y0, y);
            y1 = Math.max(y1, y);
        }
        if (y1 - y0 < gap * 1.65f || y1 - y0 > gap * 2.85f
                || maxRows < (y1 - y0 + 1) * .68f || y0 > center - gap * 1.15f
                || y1 < center + gap * .22f) return null;
        int reach = spine + Math.round(gap * .37f);
        int expanded = 0, early = 0, yFrom = bottom, yTo = top;
        for (int y = y0; y <= y1; y++) {
            for (int x = reach; x <= right; x++) {
                if (semantic[y * w + x] != 0) continue;
                if (y < center - gap * .67f) early++;
                else {
                    expanded++;
                    yFrom = Math.min(yFrom, y);
                    yTo = Math.max(yTo, y);
                }
                break;
            }
        }
        if (expanded < gap * .35f || early > gap * .18f
                || Math.abs((yFrom + yTo) * .5f - center) > gap * .38f) return null;
        return new Ink(left, right, y0 - 1, y1 + 1);
    }

    private static boolean emptyExceptOwned(byte[] gray, int width, PlayingTechniqueDetector.Staff staff,
            int opening, int closing, float ax, float bx, int left, int top, byte[] semantic, int w,
            Ink natural, Ink flat) {
        float gap = staff.gap();
        for (int x = Math.round(opening + gap * .62f); x <= Math.round(closing - gap * .48f); x++) {
            if (x >= Math.min(ax, bx) - gap * .93f && x <= Math.max(ax, bx) + gap * 1.18f) continue;
            int unexplained = 0;
            for (int y = Math.round(staff.top()); y <= Math.round(staff.bottom()); y++) {
                float row = (y - staff.top()) / gap;
                if (Math.abs(row - Math.round(row)) < .18f || (gray[y * width + x] & 255) >= 155)
                    continue;
                if (natural.owns(semantic, w, x - left, y - top)
                        || flat.owns(semantic, w, x - left, y - top)) continue;
                unexplained++;
            }
            if (unexplained >= Math.max(4, Math.round(gap * .42f))) return false;
        }
        return true;
    }

    private record Ink(int left, int right, int top, int bottom) {
        boolean owns(byte[] plane, int width, int x, int y) {
            if (x < left - 1 || x > right + 1 || y < top || y > bottom
                    || y < 0 || y >= plane.length / width) return false;
            for (int col = Math.max(left, x - 1); col <= Math.min(right, x + 1); col++)
                if (plane[y * width + col] == 0) return true;
            return false;
        }
    }
}
