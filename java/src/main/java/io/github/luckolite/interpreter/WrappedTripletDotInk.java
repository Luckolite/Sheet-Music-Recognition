// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Local dot evidence for a triplet whose owner and following attack are already proved. */
final class WrappedTripletDotInk {
    private WrappedTripletDotInk() {}

    record Center(int x, int y) {}

    static Center uniqueCore(byte[] gray, int width, int height,
            float headX, float headY, float nextX, float gap) {
        if (gray == null || gray.length != (long) width * height
                || !Float.isFinite(headX + headY + nextX + gap) || gap < 10 || gap > 30) return null;
        int left = Math.round(headX + gap * .87f);
        int right = Math.min(Math.round(headX + gap * 1.44f), Math.round(nextX - gap * .58f));
        int top = Math.round(headY - gap * .70f);
        int bottom = Math.round(headY + gap * .30f);
        int reach = Math.round(gap * .68f);
        if (left <= reach || right >= width - reach || top <= reach || bottom >= height - reach)
            return null;

        int coreRadius = Math.round(gap * .125f);
        int faceStart = Math.round(gap * .31f), faceEnd = Math.round(gap * .37f);
        int minimumCore = (int) Math.ceil(Math.pow(2 * coreRadius + 1, 2) * .76);
        int minimumCross = (int) Math.ceil((2 * coreRadius + 1) * .8);
        int faceMaximum = (int) Math.floor((faceEnd - faceStart + 1) * (2 * coreRadius + 1) * .2);
        int sameGlyph = Math.round(gap * .27f);
        Center first = null;
        Center best = null;
        int bestScore = -1;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int core = count(gray, width, x - coreRadius, x + coreRadius,
                        y - coreRadius, y + coreRadius, 105);
                if (core < minimumCore
                        || count(gray, width, x - coreRadius, x + coreRadius, y, y, 105) < minimumCross
                        || count(gray, width, x, x, y - coreRadius, y + coreRadius, 105) < minimumCross)
                    continue;
                int rightInk = count(gray, width, x + faceStart, x + faceEnd,
                        y - coreRadius, y + coreRadius, 155);
                if (rightInk > faceMaximum) continue;
                int leftInk = count(gray, width, x - faceEnd, x - faceStart,
                        y - coreRadius, y + coreRadius, 155);
                int lowerInk = count(gray, width, x - coreRadius, x + coreRadius,
                        y + faceStart, y + faceEnd, 155);
                int upperInk = count(gray, width, x - coreRadius, x + coreRadius,
                        y - faceEnd, y - faceStart, 155);
                int attachedFaces = (leftInk > faceMaximum ? 1 : 0)
                        + (lowerInk > faceMaximum ? 1 : 0) + (upperInk > faceMaximum ? 1 : 0);
                if (attachedFaces > 1) continue;
                if (lowerInk > faceMaximum && !ruleBeside(gray, width, x, y, gap, 1)
                        || upperInk > faceMaximum && !ruleBeside(gray, width, x, y, gap, -1)) continue;
                if (first == null) first = new Center(x, y);
                if (Math.abs(x - first.x) > sameGlyph || Math.abs(y - first.y) > sameGlyph)
                    return null;
                int score = core * 8 - rightInk - leftInk - lowerInk - upperInk;
                if (score > bestScore) {
                    best = new Center(x, y);
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /** A staff/slur crossing can compact the foot of the first of two printed flats.
     * Require the same pair of spines, clear middle, and tapering right-facing bowls. */
    static boolean pairedFlatOwns(byte[] gray, int width, int height, Center dot, float gap) {
        int x = dot.x(), y = dot.y();
        int top = y - Math.round(gap * 1.75f),
                taper = y + Math.round(gap * .15f),
                middle = y - Math.round(gap * .42f);
        int spineRadius = Math.round(gap * .17f);
        if (x - spineRadius < 0
                || x + Math.round(gap * 2.1f) >= width
                || top < 0
                || taper + 1 >= height) return false;
        for (int left = x - spineRadius; left <= x; left++) {
            if (!flatSpine(gray, width, left, top, y)) continue;
            for (int right = left + Math.round(gap * .98f);
                    right <= left + Math.round(gap * 1.18f);
                    right++) {
                if (!flatSpine(gray, width, right, top, y)
                        || !flatBowl(gray, width, left, middle, taper, gap)
                        || !flatBowl(gray, width, right, middle, taper, gap)) continue;
                int from = y - Math.round(gap * 1.05f), to = y - Math.round(gap * .28f);
                int space = right - Math.round(gap * .21f), clear = 0;
                for (int row = from; row <= to; row++)
                    if (count(gray, width, space, right - 1, row, row, 155)
                            <= (right - space) * .4f) clear++;
                if (clear < (to - from + 1) * .7f) continue;
                if (count(gray, width, left, x, y, y, 155) == x - left + 1) return true;
            }
        }
        return false;
    }

    static boolean singleFlatOwns(
            byte[] gray, int width, int height, Center core, float noteX, float noteY, float gap) {
        float before = noteX - core.x(), below = core.y() - noteY;
        if (before < gap * 1.4f || before > gap * 1.95f || below < gap * .45f || below > gap * .78f)
            return false;
        int left = core.x() - Math.round(gap * .49f);
        int right = core.x() + Math.round(gap * .45f);
        int top = core.y() - Math.round(gap * 1.96f);
        int bottom = core.y() + Math.round(gap * .49f);
        if (!PrintedFlatGlyph.matches(gray, width, height, left, top, right, bottom, gap))
            return false;
        int spineTop = core.y() - Math.round(gap * 1.75f);
        for (int spine = left; spine <= core.x(); spine++)
            if (flatSpine(gray, width, spine, spineTop, core.y())
                    && count(gray, width, spine, core.x(), core.y(), core.y(), 155)
                            == core.x() - spine + 1) return true;
        return false;
    }

    private static boolean flatSpine(byte[] gray, int width, int x, int top, int bottom) {
        return count(gray, width, x, x + 1, top, bottom, 155) >= 2 * (bottom - top + 1) * .96f;
    }

    private static boolean flatBowl(
            byte[] gray, int width, int x, int middle, int taper, float gap) {
        int bowlLeft = x + Math.round(gap * .48f), bowlRight = x + Math.round(gap * .74f) - 1;
        if (count(gray, width, bowlLeft, bowlRight, middle - 5, middle - 2, 155)
                < (bowlRight - bowlLeft + 1) * 4 * .55f) return false;
        int tailLeft = x + Math.round(gap * .31f), tailRight = x + Math.round(gap * .79f) - 1;
        return count(gray, width, tailLeft, tailRight, taper - 1, taper + 1, 155)
                <= (tailRight - tailLeft + 1) * 3 * .10f;
    }

    // A vertical contact must meet a short staff/ledger rail on both sides of the dot.
    private static boolean ruleBeside(byte[] gray, int width, int x, int y, float gap, int direction) {
        int far = Math.round(gap * .68f), near = Math.round(gap * .37f);
        int minimum = (int) Math.ceil((far - near + 1) * .65);
        for (int step = Math.round(gap * .125f); step <= Math.round(gap * .62f); step++) {
            int row = y + direction * step;
            if (count(gray, width, x - far, x - near, row, row, 155) >= minimum
                    && count(gray, width, x + near, x + far, row, row, 155) >= minimum)
                return true;
        }
        return false;
    }

    private static int count(byte[] gray, int width,
            int left, int right, int top, int bottom, int cutoff) {
        int ink = 0;
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++)
                if ((gray[y * width + x] & 255) < cutoff) ink++;
        return ink;
    }
}
