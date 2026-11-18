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
        return proves(labels, gray, width, height, measure, next, chord, staffs, false, false, false);
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
        return proves(labels, gray, width, height, measure, next, chord, staffs, true, false, false);
    }

    static boolean provesSeparatedTerminalFlats(byte[] labels, byte[] gray, int width, int height,
            MeasureRegion measure, MeasureRegion next, List<ScoreNoteEvent> chord,
            List<PlayingTechniqueDetector.Staff> staffs) {
        return proves(labels, gray, width, height, measure, next, chord, staffs, false, true, false);
    }

    /** Wider final measures still need two separate signs, both rules, and one shared shaft. */
    static boolean provesWidePageEnd(
            byte[] labels, byte[] gray, int width, int height,
            MeasureRegion measure, List<ScoreNoteEvent> chord,
            List<PlayingTechniqueDetector.Staff> staffs) {
        return proves(labels, gray, width, height, measure, null, chord, staffs, false, false, true);
    }

    /** Verifies two literal flats in a quarter-note chord at the end of a 3/4 system. */
    static boolean provesClosedTripleSystemEnd(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            MeasureRegion measure,
            MeasureRegion next,
            List<ScoreNoteEvent> chord) {
        return provesClosedTripleSystemEnd(labels, gray, width, height, measure, next, chord, false);
    }

    static boolean provesClosedTripleSystemEnd(
            byte[] labels, byte[] gray, int width, int height, MeasureRegion measure,
            MeasureRegion next, List<ScoreNoteEvent> chord, boolean octaveBeforeCommon) {
        if (gray == null
                || labels == null
                || gray.length != (long) width * height
                || labels.length != gray.length
                || measure.cadenceQuarterBeats() != 0
                || next != null && next.top() <= measure.bottom()
                || chord.size() != 2) return false;
        var upper = chord.get(0);
        var lower = chord.get(1);
        if (upper.pageY() > lower.pageY()) {
            var first = upper;
            upper = lower;
            lower = first;
        }
        if (upper.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                || lower.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                || upper.stemDirection() == 0
                || upper.stemDirection() != lower.stemDirection()) return false;
        float left = measure.left() * width, right = measure.right() * width, span = right - left;
        float gap = (measure.bottom() - measure.top()) * height / 8.2f;
        float ax = left + span * upper.positionInMeasure(),
                bx = left + span * lower.positionInMeasure();
        float ay = upper.pageY() * height, by = lower.pageY() * height;
        float minSpan = octaveBeforeCommon ? 5.3f : 5.5f;
        float minHeight = octaveBeforeCommon ? 3.2f : .7f;
        float maxHeight = octaveBeforeCommon ? 3.75f : 3.0f;
        if (gap < 8
                || gap > 28
                || span < gap * minSpan
                || span > gap * 9.4f
                || Math.abs(ax - bx) > gap * .3f
                || by - ay < gap * minHeight
                || by - ay > gap * maxHeight
                || octaveBeforeCommon && (upper.staffStep() - lower.staffStep() != 7 || next == null)
                || upper.positionInMeasure() < .25f
                || lower.positionInMeasure() > .67f) return false;
        float hint = measure.bottom() * height - gap * 2.1f;
        var rails =
                PrintedDirectionStaff.printedRules(
                        gray, width, height, left + span * .38f, hint, gap);
        if (rails == null) return false;
        gap = rails[1];
        float top = rails[0] - 4 * gap;
        if (top < 0 || rails[0] >= height) return false;
        var staff = new PlayingTechniqueDetector.Staff(top, rails[0], gap, 0, 1);
        int opening =
                PrintedCadenceDurations.boundaryColumn(
                        gray, width, staff, left - gap * .5f, true, false);
        int closing =
                PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, true);
        if (closing < 0)
            closing =
                    PrintedCadenceDurations.boundaryColumn(
                            gray, width, staff, right + .35f * gap, false, true);
        if (closing < 0) {
            if (octaveBeforeCommon) return false;
            closing =
                    PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, true, false);
            if (closing < 0
                    || !PrintedCadenceDurations.terminalSingleRule(
                            gray, width, height, closing, staff)) return false;
        }
        float rightClearance = octaveBeforeCommon ? 2.0f : 2.5f;
        if (opening < 0 || ax - opening < gap * 2.5f || closing - ax < gap * rightClearance)
            return false;
        return ownedPair(
                labels,
                gray,
                width,
                height,
                staff,
                opening,
                closing,
                ax,
                ay,
                bx,
                by,
                true,
                by - ay >= gap * 2.1f,
                false,
                false,
                false);
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
            boolean inline, boolean onlySeparatedFlats, boolean widePageEnd) {
        if (gray == null
                || labels == null
                || gray.length != (long) width * height
                || labels.length != gray.length
                || chord.size() != 2
                || inline && next == null
                || next != null && (next.top() <= measure.bottom()) != inline
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
        if (onlySeparatedFlats && !twoFlats) return false;
        if (twoFlats && a.pageY() > b.pageY()) {
            var upper = b;
            b = a;
            a = upper;
        }
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
            boolean singleNatural =
                    !inline
                            && a.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                            && b.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                            && by - ay >= gap * .7f
                            && by - ay <= gap * 1.48f
                            && span >= gap * 5.5f
                            && span <= gap * 10.0f;
            boolean lowerNatural =
                    !inline
                            && a.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                            && b.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                            && ay - by >= gap * 2.1f
                            && ay - by <= gap * 3.25f
                            && span >= gap * 5.5f
                            && span <= gap * (widePageEnd ? 10f : 9.5f);
            boolean separated =
                    !inline
                            && (twoFlats
                                    || a.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                                            && b.writtenAccidental()
                                                    == ScoreNoteEvent.ACCIDENTAL_FLAT)
                            && Math.abs(by - ay) >= gap * 2.1f
                            && Math.abs(by - ay) <= gap * 3.25f;
            if (gap < 6
                    || gap > 32
                    || fitted.index() != 0
                    || fitted.count() != 1
                    || onlySeparatedFlats && !separated
                    || next == null && !lowerNatural
                    || widePageEnd && (!lowerNatural || span <= gap * 9.5f)
                    || wide && !singleNatural && (twoFlats || !separated || span > gap * (widePageEnd ? 10f : 9.5f))
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
            int closing;
            if (singleNatural || lowerNatural) {
                closing =
                        PrintedCadenceDurations.boundaryColumn(
                                gray, width, staff, right, false, true);
                for (float shift : new float[] {.60f, 1.22f}) {
                    if (closing >= 0) break;
                    closing =
                            PrintedCadenceDurations.boundaryColumn(
                                    gray, width, staff, right + gap * shift, false, true);
                }
            } else {
                closing =
                        PrintedCadenceDurations.boundaryColumn(
                                gray, width, staff, rightRule, false, false);
                if (closing < 0 && !inline)
                    closing =
                            PrintedCadenceDurations.boundaryColumn(
                                    gray, width, staff, right + gap * .35f, false, false);
            }
            if (opening < 0 || closing < 0 || ax - opening < 2.5f * gap
                    || closing - ax < 2.5f * gap) continue;
            if (widePageEnd
                    && (ClosedHeadBarlineGuard.attached(
                                    gray, width, height, opening, Math.round(top),
                                    Math.round(staff.bottom()), gap)
                            || DottedDyadMeasureSeparator.continuesBeyondStaff(
                                    gray, width, height, opening, Math.round(top),
                                    Math.round(staff.bottom()), gap)
                            || !PrintedCadenceDurations.quarterShaft(
                                    gray, width, height, left, span, gap, a.stemDirection(), chord))) continue;
            if (ownedPair(
                    labels,
                    gray,
                    width,
                    height,
                    staff,
                    opening,
                    closing,
                    ax,
                    ay,
                    bx,
                    by,
                    twoFlats,
                    separated,
                    wide || onlySeparatedFlats,
                    singleNatural, widePageEnd)) return true;
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
            boolean wide,
            boolean singleNatural, boolean isolatedNatural) {
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
                gray, width, staff, opening, closing, ax, ay, bx, by, left, top, semantic, w, h, twoFlats, isolatedNatural))
            return true;
        if (wide && !singleNatural) return false;

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
        if (singleNatural && second.isEmpty()) {
            var ink =
                    RawNumeralComponents.capture(
                            semantic, w, h, 0, 0, w - 1, h - 1, () -> {}, () -> {});
            if (ink.components().stream().filter(core -> core.size() >= gap * gap * .25f).count()
                    != 1) return false;
            for (var natural : first)
                if (emptyExceptOwned(
                        gray, width, staff, opening, closing, ax, bx, left, top, semantic, w,
                        natural, null, 1)) return true;
        }
        if (wide) return false;
        for (var a : first) {
            for (var b : second) {
                int yOverlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                int xOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
                if (yOverlap > gap * .6f && xOverlap > gap * .24f) continue;
                if (emptyExceptOwned(
                        gray, width, staff, opening, closing, ax, bx, left, top, semantic, w, a, b, 1))
                    return true;
            }
        }
        return false;
    }

    /** The projected boxes can overlap while each printed sign retains its own connected ink. */
    private static boolean separatedPair(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff, int opening, int closing,
            float ax, float ay, float bx, float by, int left, int top, byte[] semantic, int w, int h,
            boolean twoFlats, boolean isolatedNatural) {
        if (semantic.length > 16_384) return false;
        float gap = staff.gap();
        var ink = RawNumeralComponents.capture(semantic, w, h, 0, 0, w - 1, h - 1, () -> {}, () -> {});
        var cores = ink.components().stream()
                .filter(core -> core.size() >= gap * gap * .35f)
                .sorted(java.util.Comparator.comparingInt(core -> core.top))
                .toList();
        if (cores.size() != 2) return false;
        boolean naturalBelow = !twoFlats && ay > by;
        var natural = cores.get(naturalBelow ? 1 : 0);
        var flat = cores.get(naturalBelow ? 0 : 1);
        if (!naturalBelow
                && (flat.left >= natural.left
                        || flat.right - natural.left < gap * .24f
                        || !twoFlats && natural.bottom - flat.top < gap * .6f)) return false;
        byte[] naturalInk = new byte[semantic.length];
        byte[] flatInk = new byte[semantic.length];
        Arrays.fill(naturalInk, (byte) 255);
        Arrays.fill(flatInk, (byte) 255);
        for (int i = 0; i < natural.size(); i++) naturalInk[natural.pixel(i)] = 0;
        for (int i = 0; i < flat.size(); i++) flatInk[flat.pixel(i)] = 0;
        var upper = twoFlats ? flats(naturalInk, w, h, ax - left, ay - top, gap)
                : naturals(naturalInk, w, h, ax - left, ay - top, gap, Math.round(staff.top()) - top);
        if (upper.isEmpty() && isolatedNatural && naturalBelow) {
            // Only the isolated semantic component reaches this check. Applying
            // staff-line suppression again can sever the two owned crossbars.
            upper = naturals(naturalInk, w, h, ax - left, ay - top, gap, h + 1);
        }
        if (upper.isEmpty()) return false;
        int flatTop = flat.top;
        List<Ink> confirmedFlats = flats(flatInk, w, h, bx - left, by - top, gap);
        if (naturalBelow && confirmedFlats.isEmpty()) {
            flatTop =
                    completeFlatSpine(
                            gray,
                            width,
                            left,
                            top,
                            flatInk,
                            w,
                            h,
                            flat.left,
                            flat.right,
                            flat.top,
                            by,
                            gap);
            if (flatTop == flat.top) return false;
            confirmedFlats = flats(flatInk, w, h, bx - left, by - top, gap);
        }
        if (confirmedFlats.isEmpty()) return false;
        for (int i = 0; i < flatInk.length; i++) if (flatInk[i] == 0) naturalInk[i] = 0;
        return emptyExceptOwned(
                gray,
                width,
                staff,
                opening,
                closing,
                ax,
                bx,
                left,
                top,
                naturalInk,
                w,
                new Ink(natural.left, natural.right, natural.top, natural.bottom),
                new Ink(flat.left, flat.right, flatTop, flat.bottom),
                twoFlats ? 2 : 1);
    }

    /**
     * The model can split the flat at a staff rule. Follow only the original narrow stroke
     * upward from the selected bowl's surviving left spine.
     */
    private static int completeFlatSpine(
            byte[] gray,
            int width,
            int left,
            int top,
            byte[] ink,
            int w,
            int h,
            int flatLeft,
            int flatRight,
            int flatTop,
            float noteY,
            float gap) {
        int earliest = Math.max(0, Math.round(noteY - top - gap * 2.25f));
        int extended = flatTop;
        for (int x = flatLeft; x <= Math.min(flatRight, flatLeft + Math.round(gap * .32f)); x++) {
            int seen = 0;
            int anchor = -1;
            for (int y = flatTop; y <= Math.min(h - 1, flatTop + Math.round(gap * .70f)); y++) {
                if (ink[y * w + x] != 0) continue;
                if (anchor < 0) anchor = y;
                seen++;
            }
            if (seen < Math.round(gap * .37f)) continue;
            int begin = anchor;
            while (begin > earliest && (gray[(top + begin - 1) * width + left + x] & 255) < 155)
                begin--;
            if (flatTop - begin < Math.round(gap * .65f)) continue;
            if (begin == earliest
                    && top + earliest > 0
                    && (gray[(top + earliest - 1) * width + left + x] & 255) < 155) continue;
            for (int y = begin; y < anchor; y++) ink[y * w + x] = 0;
            extended = Math.min(extended, begin);
        }
        return extended;
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

    /** The complete isolated flat occupies the left shoulder of a reconstructed head. */
    static boolean isolatedFlat(byte[] gray, int width, int height, float x, float y, float gap) {
        int left = Math.round(x - 2f * gap), right = Math.round(x - .58f * gap);
        int top = Math.round(y - 2.3f * gap), bottom = Math.round(y + .97f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        int w = right - left + 1, h = bottom - top + 1;
        var ink = new byte[w * h];
        for (int yy = 0; yy < h; yy++)
            for (int xx = 0; xx < w; xx++)
                ink[yy * w + xx] = (byte) ((gray[(top + yy) * width + left + xx] & 255) < 155 ? 0 : 255);
        for (int start = 0; start <= Math.round(.25f * gap); start++)
            for (int end = start + Math.round(.55f * gap);
                    end < w && end <= start + Math.round(1.1f * gap); end++)
                if (flatGlyph(ink, w, h, start, end, y + .14f * gap - top, gap) != null) return true;
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
            Ink second, int fringe) {
        float gap = staff.gap();
        for (int x = Math.round(opening + gap * .62f); x <= Math.round(closing - gap * .48f); x++) {
            if (x >= Math.min(ax, bx) - gap * .93f && x <= Math.max(ax, bx) + gap * 1.18f) continue;
            int unexplained = 0;
            for (int y = Math.round(staff.top()); y <= Math.round(staff.bottom()); y++) {
                float row = (y - staff.top()) / gap;
                if (Math.abs(row - Math.round(row)) < .18f || (gray[y * width + x] & 255) >= 155)
                    continue;
                if (first.owns(semantic, w, x - left, y - top, fringe)
                        || second != null && second.owns(semantic, w, x - left, y - top, fringe))
                    continue;
                unexplained++;
            }
            if (unexplained >= Math.max(4, Math.round(gap * .42f))) return false;
        }
        return true;
    }

    private record Ink(int left, int right, int top, int bottom) {
        boolean owns(byte[] plane, int width, int x, int y, int fringe) {
            if (x < left - fringe || x > right + fringe || y < top || y > bottom
                    || y < 0 || y >= plane.length / width) return false;
            for (int col = Math.max(left, x - fringe); col <= Math.min(right, x + fringe); col++)
                if (plane[y * width + col] == 0) return true;
            return false;
        }
    }
}
