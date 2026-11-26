// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Source-ink confirmation of a flat's single spine and lower-right bowl. */
final class PrintedFlatGlyph {
    private PrintedFlatGlyph() {}

    static boolean matchesOwnedInk(byte[] labels, byte[] gray, int width, int height,
            float noteX, float noteY, float gap) {
        if (labels == null || gray == null || labels.length != gray.length
                || gray.length != (long) width * height || gap < 9 || gap > 27) return false;
        int left = Math.round(noteX - 1.85f * gap), right = Math.round(noteX - .72f * gap);
        int top = Math.round(noteY - 1.80f * gap), bottom = Math.round(noteY + .85f * gap);
        int pad = Math.round(1.6f * gap);
        int originX = left - pad, originY = top - pad;
        int endX = right + pad, endY = bottom + pad;
        if (originX < 0 || originY < 0 || endX >= width || endY >= height) return false;
        int w = endX - originX + 1, h = endY - originY + 1;
        var glyphInk = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = (originY + y) * width + originX + x;
                glyphInk[y * w + x] = labels[at] == OmrMeasurePostProcessor.CLEF_OR_KEY
                        ? gray[at] : (byte) 255;
            }
        }
        return matches(glyphInk, w, h, left - originX, top - originY,
                right - originX, bottom - originY, gap);
    }

    static boolean matches(
            byte[] gray, int w, int h, int left, int top, int right, int bottom, float gap) {
        return matches(gray, w, h, left, top, right, bottom, gap, null);
    }

    static boolean matches(
            byte[] gray,
            int w,
            int h,
            int left,
            int top,
            int right,
            int bottom,
            float gap,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        return matchesAt(gray, w, h, left, top, right, bottom, gap, 165, false, quarterBudget)
                || matchesAt(gray, w, h, left, top, right, bottom, gap, 225, true, quarterBudget);
    }

    /** Pale spines still require a dark bowl and every original shape constraint. */
    private static boolean matchesAt(
            byte[] gray,
            int w,
            int h,
            int left,
            int top,
            int right,
            int bottom,
            float gap,
            int normal,
            boolean faded) {
        return matchesAt(gray, w, h, left, top, right, bottom, gap, normal, faded, null);
    }

    private static boolean matchesAt(
            byte[] gray,
            int w,
            int h,
            int left,
            int top,
            int right,
            int bottom,
            float gap,
            int normal,
            boolean faded,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        if (gray == null
                || right < left
                || bottom < top
                || left < 0
                || top < 0
                || right >= w
                || bottom >= h
                || bottom - top + 1 < gap * .8f) return false;
        // A staff-labelled cut can leave only the flat's bowl in the semantic box.
        // Follow its existing left spine upward in the source, never invent pixels.
        int inkLimit =
                ShadedInkWindow.flatSpineLimit(
                        gray,
                        w,
                        h,
                        left - Math.round(gap),
                        top - Math.round(gap),
                        right + Math.round(gap),
                        bottom + Math.round(gap),
                        normal,
                        quarterBudget);
        int extended = top;
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            for (int x = left; x <= left + (right - left) * .4f; x++) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                if (!dark(gray, w, x, top, inkLimit, quarterBudget)) continue;
                int blank = 0, end = top;
                {
                    if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                    for (int y = top; y >= Math.max(0, top - Math.round(gap * 1.5f)); y--) {
                        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                        if (dark(gray, w, x, y, inkLimit, quarterBudget)) {
                            end = y;
                            blank = 0;
                        } else if (++blank > 1) break;
                    }
                }

                extended = Math.min(extended, end);
            }
        }

        top = extended;
        int ww = right - left + 1, hh = bottom - top + 1;
        if (gray == null
                || left < 0
                || top < 0
                || right >= w
                || bottom >= h
                || hh < gap * 1.5f
                || hh > gap * 3.5f
                || ww < gap * .4f
                || ww > gap * 1.6f) return false;
        int spine = left, best = 0;
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            for (int x = left; x <= left + ww * .4f; x++) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                int count = 0;
                {
                    if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                    for (int y = top; y <= bottom; y++) {
                        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                        if (dark(gray, w, x, y, inkLimit, quarterBudget)) count++;
                    }
                }

                if (count > best) {
                    best = count;
                    spine = x;
                }
            }
        }

        if (best < hh * .78f) return false;
        int start = Math.max(spine + Math.round(gap * .3f), left + Math.round(ww * .6f));
        if (start > right) return false;
        int upper = 0, bowl = 0, tail = 0, clear = 0, darkBowl = 0;
        boolean ruleWithinSource = left - gap >= 0 && right + gap < w;
        int leftRuleX = ruleWithinSource ? Math.round(left - gap) : 0;
        int rightRuleX = ruleWithinSource ? Math.round(right + gap) : 0;
        float upperEnd = top + hh * .4f, tailStart = top + hh * .9f;
        float clearStart = top + hh * .5f, clearEnd = top + hh * .8f;
        int clearColumn = spine + Math.round(gap * .2f);
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            for (int y = top; y <= bottom; y++) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                boolean rule =
                        ruleWithinSource
                                && dark(gray, w, leftRuleX, y, inkLimit, quarterBudget)
                                && dark(gray, w, rightRuleX, y, inkLimit, quarterBudget);
                if (rule) continue;
                boolean ink = false;
                {
                    if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                    for (int x = start; x <= right; x++) {
                        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                        if (dark(gray, w, x, y, inkLimit, quarterBudget)) {
                            ink = true;
                            break;
                        }
                    }
                }

                if (faded && y >= upperEnd && y <= tailStart) {
                    {
                        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                        for (int x = start; x <= right; x++) {
                            if (quarterBudget != null)
                                FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                            if (dark(gray, w, x, y, Math.min(165, inkLimit), quarterBudget)) {
                                darkBowl++;
                                break;
                            }
                        }
                    }
                }
                if (ink) {
                    if (y < upperEnd) upper++;
                    else if (y > tailStart) tail++;
                    else bowl++;
                }
                if (y > clearStart && y < clearEnd) {
                    if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                    for (int x = clearColumn; x < start; x++) {
                        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                        if (!dark(gray, w, x, y, inkLimit, quarterBudget)) clear++;
                    }
                }
            }
        }

        return upper <= hh * .08f
                && tail <= 1
                && bowl >= hh * .18f
                && clear >= 3
                && (!faded || darkBowl >= hh * .18f);
    }

    private static boolean dark(byte[] gray, int w, int x, int y, int inkLimit) {
        return dark(gray, w, x, y, inkLimit, null);
    }

    private static boolean dark(
            byte[] gray,
            int w,
            int x,
            int y,
            int inkLimit,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        return (gray[y * w + x] & 255) < inkLimit;
    }

    /** Four engraved flats can share two spines visually across the staff rules. */
    static boolean stackedTrebleSignature(
            byte[] gray, int width, int height, float boundary,
            float staffBottom, float staffGap) {
        if (gray == null || gray.length != (long) width * height
                || staffGap < 8 || staffGap > 32) return false;
        int ruleX = Math.round(boundary + staffGap * 4);
        var rules = PrintedDirectionStaff.printedRules(
                gray, width, height, ruleX, staffBottom, staffGap);
        if (rules == null) return false;
        float gap = rules[1];
        int top = Math.round(rules[0] - 4 * gap);
        int start = Math.round(boundary + .55f * gap);
        int end = Math.round(boundary + 1.5f * gap);
        for (int meter = start; meter <= end; meter += Math.max(1, Math.round(.12f * gap))) {
            int left = Math.round(meter - 3.08f * gap);
            int right = Math.round(meter - .10f * gap);
            if (CommonTimeMeter.read(gray, width, height, meter,
                            Math.round(meter + 2.32f * gap), top, gap, 0) == 4
                    && fourStackedBowls(gray, width, height, left, right, top, gap)) return true;
        }
        return false;
    }

    private record Bowl(float x, float y) {}

    private static boolean fourStackedBowls(
            byte[] gray, int width, int height, int left, int right, int top, float gap) {
        int upper = Math.round(top - 1.4f * gap);
        int lower = Math.round(top + 3.4f * gap);
        int across = right - left + 1;
        int down = lower - upper + 1;
        int railRadius = Math.max(1, Math.round(.15f * gap));
        if (left < 0 || right >= width || upper < 0 || lower >= height
                || across < 2.4f * gap || across > 3.2f * gap) return false;
        boolean[] visited = new boolean[across * down];
        for (int y = upper; y <= lower; y++) {
            int rule = Math.round((y - top) / gap);
            float ruleY = top + rule * gap;
            boolean onRule = rule >= 0 && rule < 5 && Math.abs(y - ruleY) < railRadius;
            for (int x = left; x <= right; x++) {
                boolean ink = stackedInk(gray, width, x, y);
                if (onRule) {
                    ink &= stackedInk(gray, width, x, Math.round(ruleY) - railRadius)
                            && stackedInk(gray, width, x, Math.round(ruleY) + railRadius);
                }
                visited[(y - upper) * across + x - left] = ink;
            }
        }
        Bowl[] bowls = new Bowl[4];
        int count = 0;
        int[] queue = new int[visited.length];
        for (int seed = 0; seed < visited.length; seed++) {
            if (visited[seed]) continue;
            int tail = 0;
            queue[tail++] = seed;
            visited[seed] = true;
            int x0 = across, x1 = -1, y0 = down, y1 = -1;
            for (int head = 0; head < tail; head++) {
                int at = queue[head], x = at % across, y = at / across;
                x0 = Math.min(x, x0);
                x1 = Math.max(x, x1);
                y0 = Math.min(y, y0);
                y1 = Math.max(y, y1);
                if (x > 0) tail = enqueueBowl(visited, queue, at - 1, tail);
                if (x + 1 < across) tail = enqueueBowl(visited, queue, at + 1, tail);
                if (y > 0) tail = enqueueBowl(visited, queue, at - across, tail);
                if (y + 1 < down) tail = enqueueBowl(visited, queue, at + across, tail);
            }
            int span = x1 - x0 + 1, depth = y1 - y0 + 1;
            if (x0 == 0 || x1 == across - 1 || y0 == 0 || y1 == down - 1
                    || span < .18f * gap || depth < .25f * gap || tail < .055f * gap * gap)
                continue;
            if (span > .68f * gap || depth > .84f * gap || count == bowls.length
                    || !stackedSpine(gray, width, left + x0, upper + y0, upper + y1, gap))
                return false;
            bowls[count++] = new Bowl(left + (x0 + x1) * .5f, (upper + (y0 + y1) * .5f - top) / gap);
        }
        if (count != 4) return false;
        java.util.Arrays.sort(bowls, java.util.Comparator.comparingDouble(Bowl::y));
        // Two columns, E and B at the left, D and A at the right.
        float spacing = bowls[1].x - bowls[0].x;
        return spacing >= 1.1f * gap && spacing <= 1.9f * gap
                && Math.abs(bowls[2].x - bowls[0].x) <= .24f * gap
                && Math.abs(bowls[3].x - bowls[1].x) <= .24f * gap
                && nearBowl(bowls[0], .43f) && nearBowl(bowls[1], .93f)
                && nearBowl(bowls[2], 1.93f) && nearBowl(bowls[3], 2.43f);
    }

    private static boolean nearBowl(Bowl bowl, float y) {
        return Math.abs(bowl.y - y) <= .29f;
    }

    private static int enqueueBowl(boolean[] visited, int[] queue, int at, int tail) {
        if (visited[at]) return tail;
        visited[at] = true;
        queue[tail] = at;
        return tail + 1;
    }

    private static boolean stackedSpine(
            byte[] gray, int width, int bowlLeft, int bowlTop, int bowlBottom, float gap) {
        int above = Math.round(gap * .65f);
        for (int x = Math.max(0, bowlLeft - Math.round(gap * .29f)); x < bowlLeft; x++) {
            int stem = 0, body = 0;
            for (int y = Math.max(0, bowlTop - above); y <= bowlBottom; y++) {
                if (stackedInk(gray, width, x, y)) {
                    body++;
                    if (y < bowlTop - 2) stem++;
                }
            }
            if (stem >= .72f * (above - 2)
                    && body >= .85f * (bowlBottom - bowlTop + above + 1)) return true;
        }
        return false;
    }

    private static boolean stackedInk(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 255) < 145;
    }

}
