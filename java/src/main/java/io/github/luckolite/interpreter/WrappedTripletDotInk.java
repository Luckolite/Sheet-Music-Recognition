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
