// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Original ink proof for one closed, twice-beamed group of four shared chord shafts. */
final class ClosedFourDyadBeam {
    private ClosedFourDyadBeam() {}

    record Head(float x, float top, float bottom) {}
    private record Stem(int left, int right, int topTip) {}

    static boolean proves(byte[] gray, int width, int height, Head[] heads, float gap, int direction) {
        return proves(gray, width, height, heads, gap, direction, 4.7f, Float.NaN, false);
    }

    static boolean provesWithAccidentalSpacing(
            byte[] gray, int width, int height, Head[] heads, float gap, int direction) {
        return proves(gray, width, height, heads, gap, direction, 4.95f, Float.NaN, false);
    }

    static boolean provesOctaves(
            byte[] gray, int width, int height, Head[] heads, float gap, float staffBottom) {
        return provesOctaves(gray, width, height, heads, gap, staffBottom, 1, false);
    }

    static boolean provesOctaves(
            byte[] gray,
            int width,
            int height,
            Head[] heads,
            float gap,
            float staffBottom,
            int direction,
            boolean allowInnerRailStaffCrossing) {
        return Float.isFinite(staffBottom)
                && proves(
                        gray,
                        width,
                        height,
                        heads,
                        gap,
                        direction,
                        4.7f,
                        staffBottom,
                        allowInnerRailStaffCrossing);
    }

    /** A verified adjacent barline can enter the left beam closure sample. */
    static boolean provesOctavesAfterRule(byte[] gray, int width, int height, Head[] heads,
            float gap, float staffBottom, int direction, int leftRule) {
        return Float.isFinite(staffBottom)
                && proves(gray, width, height, heads, gap, direction, 4.7f, staffBottom, false, leftRule, -1);
    }

    static boolean provesOctavesWithSteppedStem(byte[] gray, int width, int height, Head[] heads,
            float gap, float staffBottom, int direction, int steppedIndex) {
        return heads.length == 4 && steppedIndex > 0 && steppedIndex < 3
                && Float.isFinite(staffBottom)
                && proves(gray, width, height, heads, gap, direction, 4.7f, staffBottom, false, -1, steppedIndex);
    }

    /** These independently measured five rules may cross a tip or the seam in one shaft span. */
    static boolean provesAcrossStaffRules(byte[] gray, int width, int height, Head[] heads,
            float gap, float staffBottom, int direction) {
        return Float.isFinite(staffBottom) && proves(gray, width, height, heads,
                gap, direction, 4.7f, staffBottom, false, -1, -1, 1);
    }

    static boolean provesAcrossDistinctStaffRules(byte[] gray, int width, int height,
            Head[] heads, float gap, float staffBottom, int direction) {
        return Float.isFinite(staffBottom) && proves(gray, width, height, heads,
                gap, direction, 4.7f, staffBottom, false, -1, -1, 2);
    }

    private static boolean proves(
            byte[] gray, int width, int height, Head[] heads, float gap, int direction,
            float maximumSpacing, float staffBottom, boolean allowInnerRailStaffCrossing) {
        return proves(gray, width, height, heads, gap, direction, maximumSpacing, staffBottom,
                allowInnerRailStaffCrossing, -1, -1);
    }

    private static boolean proves(
            byte[] gray,
            int width,
            int height,
            Head[] heads,
            float gap,
            int direction,
            float maximumSpacing,
            float staffBottom,
            boolean allowInnerRailStaffCrossing,
            int leftRule,
            int steppedIndex) {
        return proves(gray, width, height, heads, gap, direction, maximumSpacing,
                staffBottom, allowInnerRailStaffCrossing, leftRule, steppedIndex, 0);
    }

    static boolean provesBeforeStaffRule(byte[] gray, int width, int height, Head[] heads,
            float gap, float staffBottom, int direction, int rightRule) {
        return Float.isFinite(staffBottom) && rightRule >= 0
                && proves(gray, width, height, heads, gap, direction, 4.7f, staffBottom,
                        false, -1, -1, 2, rightRule);
    }

    private static boolean proves(
            byte[] gray, int width, int height, Head[] heads, float gap, int direction,
            float maximumSpacing, float staffBottom, boolean allowInnerRailStaffCrossing,
            int leftRule, int steppedIndex, int staffRuleSpans) {
        return proves(gray, width, height, heads, gap, direction, maximumSpacing, staffBottom,
                allowInnerRailStaffCrossing, leftRule, steppedIndex, staffRuleSpans, -1);
    }

    private static boolean proves(
            byte[] gray, int width, int height, Head[] heads, float gap, int direction,
            float maximumSpacing, float staffBottom, boolean allowInnerRailStaffCrossing,
            int leftRule, int steppedIndex, int staffRuleSpans, int rightRule) {
        boolean allStaffRules = staffRuleSpans > 0;

        if (gap < 8 || gap > 32 || direction == 0) return false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        Stem[] stems = new Stem[heads.length];
        float maximumHeight = Float.isFinite(staffBottom) ? 3.8f : 3.1f;
        for (int i = 0; i < heads.length; i++) {
            var head = heads[i];
            if (head.bottom - head.top < gap * .60f || head.bottom - head.top > gap * maximumHeight)
                return false;
            if (i > 0
                    && (head.x - heads[i - 1].x < gap * 1.35f
                            || head.x - heads[i - 1].x > gap * maximumSpacing)) return false;
            var upper = PrintedBeamShafts.at(gray, width, height, head.x, head.top, gap, budget);
            var lower = PrintedBeamShafts.at(gray, width, height, head.x, head.bottom, gap, budget);
            if (upper.size() != 1 || lower.size() != 1) return false;
            var a = upper.get(0);
            var b = lower.get(0);
            int left = Math.max(a.left(), b.left()), right = Math.min(a.right(), b.right());
            if (a.direction() != direction || b.direction() != direction) return false;
            if (i == steppedIndex) {
                stems[i] = steppedStem(gray, width, a, b, gap, direction);
                if (stems[i] == null) return false;
            } else if (left <= right && Math.abs(a.endY() - b.endY()) <= gap * .18f) {
                stems[i] = new Stem(left, right, a.endY());
            } else {
                return false;
            }
        }
        int x0 = (stems[0].left + stems[0].right) / 2;
        int x1 = (stems[3].left + stems[3].right) / 2;
        float slope = (stems[3].topTip - stems[0].topTip) / (float)(x1 - x0);
        if (Math.abs(slope) > .4f) return false;
        for (int offset = 1; offset <= Math.round(gap * .30f); offset++) {
            float center = stems[0].topTip - direction * offset;
            if (!closed(gray, width, height, x0, x1, center, slope, gap, allStaffRules ? staffBottom : Float.NaN, leftRule, rightRule, allStaffRules)) continue;
            for (int sep = Math.round(gap * .45f); sep <= Math.round(gap * 1.05f); sep++) {
                float inner = center - direction * sep;
                if (closed(
                                gray,
                                width,
                                height,
                                x0,
                                x1,
                                inner,
                                slope,
                                gap,
                                allStaffRules || allowInnerRailStaffCrossing && direction == 1
                                        ? staffBottom
                                        : Float.NaN,
                                leftRule, rightRule,
                                allStaffRules)
                        && separated(
                                gray,
                                width,
                                height,
                                x0,
                                stems,
                                center,
                                inner,
                                slope,
                                gap,
                                staffBottom,
                                steppedIndex >= 0,
                                staffRuleSpans)) return true;
            }
        }
        return false;
    }

    /** One pair of thick rails ends at the penultimate shaft. Only the primary reaches the last. */
    static boolean provesTwoSixteenthsAndEighth(
            byte[] gray, int width, int height, Head[] heads, float gap) {
        var budget = new PrintedBeamShafts.Budget(100_000);
        var shafts = new Stem[3];
        for (int i = 0; i < 3; i++) {
            var head = heads[i];
            if (head.bottom - head.top < gap * .65f
                    || head.bottom - head.top > gap * 1.4f
                    || i > 0
                            && (head.x - heads[i - 1].x < gap * 2.5f
                                    || head.x - heads[i - 1].x > gap * 3.7f)) return false;
            var upper = PrintedBeamShafts.at(gray, width, height, head.x, head.top, gap, budget);
            var lower = PrintedBeamShafts.at(gray, width, height, head.x, head.bottom, gap, budget);
            if (upper.size() != 1 || upper.get(0).direction() != 1) return false;
            var a = upper.get(0);
            if (i < 2) {
                if (lower.size() != 1 || lower.get(0).direction() != 1) return false;
                var b = lower.get(0);
                int left = Math.max(a.left(), b.left()), right = Math.min(a.right(), b.right());
                if (left > right || Math.abs(a.endY() - b.endY()) > gap * .18f) return false;
                shafts[i] = new Stem(left, right, a.endY());
            } else {
                // The source has an actual broken downward shaft below the lower head.
                if (!lower.isEmpty()
                        || a.right() - a.left() != 1
                        || a.endY() - head.bottom < gap * 1.2f
                        || a.endY() - head.bottom > gap * 1.6f) return false;
                shafts[i] = new Stem(a.left(), a.right(), a.endY());
            }
        }
        int x0 = (shafts[0].left + shafts[0].right) / 2;
        int x1 = (shafts[1].left + shafts[1].right) / 2;
        int x2 = shafts[2].left;
        int pad = Math.max(4, Math.round(gap * .30f));
        int inset = Math.round(gap * .36f), reach = Math.round(gap * .79f);
        if (x0 - reach < 0 || x2 + reach >= width) return false;
        for (int before = Math.round(gap * .18f); before <= gap * .46f; before++) {
            float y0 = shafts[0].topTip - before;
            for (int after = Math.round(gap * .95f); after <= gap * 1.45f; after++) {
                int y2 = shafts[2].topTip + after;
                float slope = (y2 - y0) / (x2 - x0);
                float middle = y0 + slope * (x1 - x0);
                if (Math.abs(slope) > .32f
                        || shafts[1].topTip - middle < gap * .12f
                        || shafts[1].topTip - middle > gap * .50f
                        || coverage(gray, width, height, x0, x0 + pad, x2 - pad, y0, slope, 2)
                                < .98f
                        || coverage(gray, width, height, x0, x0 - reach, x0 - inset, y0, slope, 2)
                                > .15f
                        || coverage(gray, width, height, x0, x2 + inset, x2 + reach, y0, slope, 2)
                                > .15f
                        || !brokenShaft(gray, width, height, x2, shafts[2].topTip, y2, gap))
                    continue;
                boolean remoteRail = false;
                for (int offset = Math.round(gap * .54f); offset <= gap * 1.12f; offset++)
                    if (coverage(gray, width, height, x0, x1 + pad, x2 - pad, y0 - offset, slope, 2)
                            > .60f) remoteRail = true;
                if (remoteRail) continue;
                for (int depth = Math.round(gap * .62f); depth <= gap * 1.05f; depth++) {
                    if (coverage(gray, width, height, x0, x0 + pad, x1 - pad, y0 - depth, slope, 2)
                                    < .96f
                            || coverage(
                                            gray,
                                            width,
                                            height,
                                            x0,
                                            x0 + pad,
                                            x1 - pad,
                                            y0 - depth * .5f,
                                            slope,
                                            0)
                                    > .50f) continue;
                    return true;
                }
            }
        }
        return false;
    }

    /** Both chord heads reach the primary rail in an intact two-sixteenth and eighth close. */
    static boolean provesIntactTwoSixteenthsAndEighth(
            byte[] gray, int width, int height, Head[] heads, float gap, int direction) {
        if (heads.length != 3 || gap < 12 || gap > 23) return false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        int[] x = new int[3], tip = new int[3];
        for (int i = 0; i < heads.length; i++) {
            var h = heads[i];
            if (h.bottom - h.top < gap * .65f || h.bottom - h.top > gap * 1.4f
                    || i > 0 && (h.x - heads[i - 1].x < gap * 2.5f
                            || h.x - heads[i - 1].x > gap * 3.7f)) return false;
            var upper = PrintedBeamShafts.at(gray, width, height, h.x, h.top, gap, budget);
            var lower = PrintedBeamShafts.at(gray, width, height, h.x, h.bottom, gap, budget);
            if (upper.size() != 1 || lower.size() != 1) return false;
            var a = upper.get(0);
            var b = lower.get(0);
            int left = Math.max(a.left(), b.left()), right = Math.min(a.right(), b.right());
            if (a.direction() != direction || b.direction() != direction || left > right
                    || Math.abs(a.endY() - b.endY()) > gap * .18f) return false;
            x[i] = (left + right) / 2;
            tip[i] = direction > 0 ? Math.min(a.endY(), b.endY()) : Math.max(a.endY(), b.endY());
        }
        float slope = (tip[2] - tip[0]) / (float) (x[2] - x[0]);
        if (Math.abs(slope) > .32f || Math.abs(tip[0] + slope * (x[1] - x[0]) - tip[1]) > gap * .22f)
            return false;
        int pad = Math.round(gap * .34f), reach = Math.round(gap * .75f);
        for (int inset = Math.round(gap * .08f); inset <= Math.round(gap * .32f); inset++) {
            float primary = tip[0] - direction * inset;
            if (coverage(gray, width, height, x[0], x[0] + pad, x[2] - pad, primary, slope, 2) < .94f
                    || coverage(gray, width, height, x[0], x[0] - reach, x[0] - pad, primary, slope, 2) > .18f
                    || coverage(gray, width, height, x[0], x[2] + pad, x[2] + reach, primary, slope, 2) > .18f)
                continue;
            for (int depth = Math.round(gap * .52f); depth <= Math.round(gap * 1.10f); depth++) {
                float secondary = primary - direction * depth;
                if (coverage(gray, width, height, x[0], x[0] + pad, x[1] - pad, secondary, slope, 2) >= .91f
                        && coverage(gray, width, height, x[0], x[1] + pad, x[2] - pad, secondary, slope, 2) <= .22f
                        && coverage(gray, width, height, x[0], x[0] + pad, x[1] - pad,
                                primary - direction * depth * .5f, slope, 0) <= .50f)
                    return true;
            }
        }
        return false;
    }

    private static boolean brokenShaft(
            byte[] gray, int width, int height, int x, int cap, int rail, float gap) {
        if (x < 0 || x + 1 >= width || cap < 0 || rail >= height) return false;
        int dark = 0, whiteRun = 0, largestGap = 0;
        for (int y = cap + 1; y < rail - Math.round(gap * .35f); y++) {
            boolean shaft =
                    (gray[y * width + x] & 255) < 170 && (gray[y * width + x + 1] & 255) < 170;
            if (shaft) {
                dark++;
                whiteRun = 0;
            } else {
                largestGap = Math.max(largestGap, ++whiteRun);
            }
        }
        return dark >= 2 && largestGap <= Math.round(gap * .42f);
    }

    private static float coverage(
            byte[] gray,
            int width,
            int height,
            int origin,
            int first,
            int last,
            float y,
            float slope,
            int radius) {
        if (first < 0 || last >= width || last < first) return 0;
        int filled = 0;
        for (int x = first; x <= last; x++)
            if (core(gray, width, height, x, y + slope * (x - origin), radius)) filled++;
        return filled / (float) (last - first + 1);
    }

    private static boolean closed(
            byte[] gray,
            int width,
            int height,
            int first,
            int last,
            float center,
            float slope,
            float gap,
            float staffBottom,
            int leftRule, int rightRule, boolean allStaffRules) {
        int radius = Math.max(1, Math.round(gap * .075f));
        int full = 0;
        for (int x = first; x <= last; x++)
            if (core(gray, width, height, x, center + slope * (x - first), radius)) full++;
        if (full < (last - first + 1) * .94f) return false;
        int begin = Math.round(gap * .4f), end = Math.round(gap * .9f);
        int staffPixels = 0;
        for (int side : new int[]{-1, 1}) {
            int edge = side < 0 ? first : last;
            int again = 0;
            for (int i = begin; i <= end; i++) {
                int x = edge + side * i;
                if (x < 0 || x >= width) return false;
                float row = center + slope * (x - first);
                // An extrapolated inner rail can cross the independently established staff rule.
                if (!core(gray, width, height, x, row, radius)) continue;
                if (side < 0 && leftRule >= 0 && first - leftRule >= gap * .6f
                        && first - leftRule <= gap * 1.5f && Math.abs(x - leftRule) <= gap * .23f)
                    continue;
                if (side > 0 && rightRule >= 0 && rightRule - last >= gap * .6f
                        && rightRule - last <= gap * 1.5f && Math.abs(x - rightRule) <= gap * .23f)
                    continue;
                boolean onRule = allStaffRules
                        ? printedStaffRule(gray, width, height, x, row, staffBottom, gap)
                        : Math.abs(row - staffBottom) <= gap * .17f;
                if (onRule) staffPixels++;
                else again++;
            }
            if (again > (end - begin + 1) * .20f) return false;
        }
        return allStaffRules || staffPixels <= Math.round(gap * .20f);
    }

    static boolean sharesSteppedDownstem(byte[] ink, int width,
            PrintedBeamShafts.Shaft upper, PrintedBeamShafts.Shaft lower, float gap) {
        return steppedStem(ink, width, upper, lower, gap, 1) != null;
    }

    private static Stem steppedStem(byte[] gray, int width, PrintedBeamShafts.Shaft above,
            PrintedBeamShafts.Shaft below, float gap, int direction) {
        var headSide = direction > 0 ? above : below;
        var beamSide = direction > 0 ? below : above;
        int extra = (beamSide.endY() - headSide.endY()) * direction;
        if (extra < gap * 1.2f || extra > gap * 3.5f) return null;
        int c0 = headSide.right() < beamSide.left() ? headSide.right() : headSide.left();
        int c1 = headSide.right() < beamSide.left() ? beamSide.left() : beamSide.right();
        if (Math.abs(c0 - c1) != 1) return null;
        int begin = Math.max(Math.min(above.startY(), above.endY()), Math.min(below.startY(), below.endY()));
        int end = Math.min(Math.max(above.startY(), above.endY()), Math.max(below.startY(), below.endY()));
        int joined = 0;
        for (int y = begin; y <= end; y++) {
            boolean both = (gray[y * width + c0] & 255) < 165 && (gray[y * width + c1] & 255) < 165;
            joined = both ? joined + 1 : 0;
            if (joined >= gap * .55f) return new Stem(beamSide.left(), beamSide.right(), beamSide.endY());
        }
        return null;
    }

    private static boolean separated(
            byte[] gray,
            int width,
            int height,
            int x0,
            Stem[] stems,
            float outer,
            float inner,
            float slope,
            float gap,
            float staffBottom,
            boolean followNarrowPaperSeam, int staffRuleSpans) {
        int margin = Math.round(gap * .23f);
        boolean allStaffRules = staffRuleSpans > 0;
        int usedSpans = 0, usedRules = 0;
        for (int i = 1; i < stems.length; i++) {
            int left = stems[i - 1].right + margin, right = stems[i].left - margin;
            int blank = 0, staffInk = 0, rules = 0;
            for (int x = left; x <= right; x++) {
                float middle = (outer + inner) * .5f + slope * (x - x0);
                if (!core(gray, width, height, x, middle, 0)
                        || followNarrowPaperSeam
                                && (!core(gray, width, height, x, middle - 1, 0)
                                        || !core(gray, width, height, x, middle + 1, 0))) blank++;
                else if (allStaffRules
                        ? printedStaffRule(gray, width, height, x, middle, staffBottom, gap)
                        : Math.abs(middle - staffBottom) <= gap * .17f) {
                    staffInk++;
                    rules |= 1 << Math.round((staffBottom - middle) / gap);
                }
            }
            int samples = right - left + 1;
            if (right - left < margin) return false;
            if (blank >= samples * .75f) continue;
            // At most one bridge may cross the independently measured bottom staff rule.
            if (blank + staffInk < samples * .95f) return false;
            if (allStaffRules) {
                if (Integer.bitCount(rules) != 1 || (rules & usedRules) != 0
                        || ++usedSpans > staffRuleSpans) return false;
                usedRules |= rules;
            } else if (usedSpans++ != 0 || blank < samples * .40f) return false;
        }
        return true;
    }


    private static boolean printedStaffRule(
            byte[] gray, int width, int height, int x, float y, float bottom, float gap) {
        int line = Math.round((bottom - y) / gap);
        if (line < 0 || line > 4 || Math.abs(y - (bottom - line * gap)) > gap * .18f) return false;
        int extent = Math.round(gap * .74f);
        if (x - extent < 0 || x + extent >= width) return false;
        int row = Math.round(y);
        for (int sample = row - 1; sample <= row + 1; sample++) {
            int dark = 0;
            for (int i = x - extent; i <= x + extent; i++)
                if (core(gray, width, height, i, sample, 0)) dark++;
            if (dark >= (2 * extent + 1) * .80f) return true;
        }
        return false;
    }

    private static boolean core(byte[] gray, int width, int height, int x, float y, int radius) {
        int row = Math.round(y);
        if (row-radius < 0 || row+radius >= height) return false;
        for (int yy = row-radius; yy <= row+radius; yy++)
            if ((gray[yy*width+x] & 255) >= 162) return false;
        return true;
    }
}
