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
        return proves(labels, gray, width, height, measure, next, chord, staffs, false);
    }

    static boolean provesBeforeTripleRepeat(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            MeasureRegion measure,
            MeasureRegion next,
            List<ScoreNoteEvent> chord,
            List<PlayingTechniqueDetector.Staff> staffs) {
        return proves(labels, gray, width, height, measure, next, chord, staffs, true);
    }

    private static boolean proves(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            MeasureRegion measure,
            MeasureRegion next,
            List<ScoreNoteEvent> chord,
            List<PlayingTechniqueDetector.Staff> staffs,
            boolean inline) {
        if (gray == null
                || labels == null
                || gray.length != (long) width * height
                || labels.length != gray.length
                || chord.size() != 2
                || next == null
                || (next.top() <= measure.bottom()) != inline
                || measure.cadenceQuarterBeats() != 0) return false;
        var a = chord.get(0);
        var b = chord.get(1);
        if (a.unbeamedDurationBeats() != 1 || b.unbeamedDurationBeats() != 1
                || a.stemDirection() == 0 || a.stemDirection() != b.stemDirection()
                || a.staffStep() == b.staffStep()) return false;
        if (b.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL) {
            a = chord.get(1);
            b = chord.get(0);
        }
        boolean twoFlats =
                a.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                        && b.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT;
        if (inline && (!twoFlats || Math.abs(a.staffStep() - b.staffStep()) != 7)) return false;
        if (!twoFlats
                && (a.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                        || b.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                                && b.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY))
            return false;

        float left = measure.left() * width, right = measure.right() * width;
        float span = right - left;
        float ax = left + span * a.positionInMeasure(), bx = left + span * b.positionInMeasure();
        float ay = a.pageY() * height, by = b.pageY() * height;
        if (a.positionInMeasure() < .25f || a.positionInMeasure() > .68f) return false;
        for (var fitted : staffs) {
            float gap = fitted.gap();
            boolean wide = span > gap * (inline ? 6.25f : 8.5f);
            boolean separated = !inline
                    && a.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    && b.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                    && by - ay >= gap * 2.1f && by - ay <= gap * 3.25f;
            if (gap < 6
                    || gap > 32
                    || fitted.index() != 0
                    || fitted.count() != 1
                    || wide && (!separated || span > gap * 9.5f)
                    || twoFlats && span < gap * (inline ? 4.5f : 5.5f)
                    || inline && Math.abs(measure.top() - next.top()) * height > gap * .5f
                    || Math.abs(ax - bx) > gap * .30f
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
            float rightRule = inline ? (right + next.left() * width) * .5f : right;
            int closing =
                    PrintedCadenceDurations.boundaryColumn(
                            gray, width, staff, rightRule, false, false);
            if (closing < 0 && !inline)
                closing =
                        PrintedCadenceDurations.boundaryColumn(
                                gray, width, staff, right + gap * .35f, false, false);
            if (opening < 0 || closing < 0 || ax - opening < 2.5f * gap
                    || closing - ax < 2.5f * gap) continue;
            if (ownedPair(
                    labels, gray, width, height, staff, opening, closing, ax, ay, bx, by,
                    twoFlats, separated, wide))
                return true;
        }
        return false;
    }

    private static boolean ownedPair(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff,
            int opening,
            int closing,
            float ax,
            float ay,
            float bx,
            float by,
            boolean twoFlats,
            boolean separated,
            boolean wide) {
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
        if (separated && separatedPair(
                gray, width, staff, opening, closing, ax, ay, bx, by, left, top, semantic, w, h))
            return true;
        if (wide) return false;

        List<Ink> first =
                twoFlats
                        ? flats(semantic, w, h, ax - left, ay - top, gap)
                        : naturals(
                                semantic,
                                w,
                                h,
                                ax - left,
                                ay - top,
                                gap,
                                Math.round(staff.top()) - top);
        if (first.isEmpty()) return false;
        var second = flats(semantic, w, h, bx - left, by - top, gap);
        for (var a : first) {
            for (var b : second) {
                int yOverlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                int xOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
                if (yOverlap > gap * .6f && xOverlap > gap * .24f) continue;
                if (emptyExceptOwned(
                        gray, width, staff, opening, closing, ax, bx, left, top, semantic, w, a, b))
                    return true;
            }
        }
        return false;
    }

    /** The projected boxes can overlap while each printed sign retains its own connected ink. */
    private static boolean separatedPair(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff, int opening, int closing,
            float ax, float ay, float bx, float by, int left, int top, byte[] semantic, int w, int h) {
        if (semantic.length > 16_384) return false;
        float gap = staff.gap();
        var ink = RawNumeralComponents.capture(semantic, w, h, 0, 0, w - 1, h - 1, () -> {}, () -> {});
        var cores = ink.components().stream()
                .filter(core -> core.size() >= gap * gap * .35f)
                .sorted(java.util.Comparator.comparingInt(core -> core.top))
                .toList();
        if (cores.size() != 2) return false;
        var natural = cores.get(0);
        var flat = cores.get(1);
        if (flat.left >= natural.left || flat.right - natural.left < gap * .24f
                || natural.bottom - flat.top < gap * .6f) return false;
        byte[] naturalInk = new byte[semantic.length];
        byte[] flatInk = new byte[semantic.length];
        Arrays.fill(naturalInk, (byte) 255);
        Arrays.fill(flatInk, (byte) 255);
        for (int i = 0; i < natural.size(); i++) naturalInk[natural.pixel(i)] = 0;
        for (int i = 0; i < flat.size(); i++) flatInk[flat.pixel(i)] = 0;
        if (naturals(naturalInk, w, h, ax - left, ay - top, gap, Math.round(staff.top()) - top).isEmpty()
                || flats(flatInk, w, h, bx - left, by - top, gap).isEmpty()) return false;
        for (int i = 0; i < flat.size(); i++) naturalInk[flat.pixel(i)] = 0;
        return emptyExceptOwned(
                gray, width, staff, opening, closing, ax, bx, left, top, naturalInk, w,
                new Ink(natural.left, natural.right, natural.top, natural.bottom),
                new Ink(flat.left, flat.right, flat.top, flat.bottom));
    }

    private static List<Ink> naturals(
            byte[] semantic, int w, int h, float x, float y, float gap, int staffTop) {
        var matches = new ArrayList<Ink>();
        int right = Math.min(w - 1, Math.round(x - gap * .87f));
        for (int a = Math.max(0, Math.round(x - gap * 3.05f)); a < right; a++) {
            for (int b = Math.max(a + Math.round(gap * .55f), Math.round(x - gap * 2.35f));
                    b <= Math.min(right, a + Math.round(gap * 1.06f));
                    b++) {
                if (OmrScoreInterpreter.provesNaturalMeterInk(
                        semantic, w, h, a, b, y, gap, staffTop))
                    matches.add(
                            new Ink(
                                    a,
                                    b,
                                    Math.max(0, Math.round(y - gap * 1.85f)),
                                    Math.min(h - 1, Math.round(y + gap * 1.85f))));
            }
        }
        return matches;
    }

    private static List<Ink> flats(byte[] semantic, int w, int h, float x, float y, float gap) {
        var matches = new ArrayList<Ink>();
        int limit = Math.min(w - 1, Math.round(x - gap * 1.27f));
        for (int a = Math.max(0, Math.round(x - gap * 3.10f)); a < limit; a++) {
            for (int b = a + Math.round(gap * .56f);
                    b <= Math.min(w - 1, a + Math.round(gap * 1.10f));
                    b++) {
                var flat = flatGlyph(semantic, w, h, a, b, y, gap);
                if (flat != null) matches.add(flat);
            }
        }
        return matches;
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

    private static boolean emptyExceptOwned(
            byte[] gray,
            int width,
            PlayingTechniqueDetector.Staff staff,
            int opening,
            int closing,
            float ax,
            float bx,
            int left,
            int top,
            byte[] semantic,
            int w,
            Ink first,
            Ink second) {
        float gap = staff.gap();
        for (int x = Math.round(opening + gap * .62f); x <= Math.round(closing - gap * .48f); x++) {
            if (x >= Math.min(ax, bx) - gap * .93f && x <= Math.max(ax, bx) + gap * 1.18f) continue;
            int unexplained = 0;
            for (int y = Math.round(staff.top()); y <= Math.round(staff.bottom()); y++) {
                float row = (y - staff.top()) / gap;
                if (Math.abs(row - Math.round(row)) < .18f || (gray[y * width + x] & 255) >= 155)
                    continue;
                if (first.owns(semantic, w, x - left, y - top)
                        || second.owns(semantic, w, x - left, y - top)) continue;
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
