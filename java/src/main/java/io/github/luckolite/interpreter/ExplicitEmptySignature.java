// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** A complete opening clef followed by a printed 4/4 can prove an empty key slot. */
final class ExplicitEmptySignature {
    private ExplicitEmptySignature() {}

    static boolean matches(
            byte[] gray,
            int width,
            int height,
            float clefRight,
            float firstHead,
            float bottom,
            float gap) {
        if (gray == null
                || gap < 4
                || !Float.isFinite(firstHead)
                || firstHead - clefRight < gap * 2) return false;
        int top = Math.max(1, Math.round(bottom - gap * 4)),
                last = Math.min(height - 2, Math.round(bottom));
        int from = Math.max(1, Math.round(clefRight + gap * 1.8f));
        int to =
                Math.min(
                        width - 2,
                        Math.round(Math.min(clefRight + gap * 3.8f, firstHead - gap * .4f)));
        for (int column = from; column <= to; column++) {
            int digitLeft = openFourPairLeft(gray, width, height, column, top, gap);
            if (digitLeft < 0) {
                if (!OmrMeasurePostProcessor.stackedFourCounters(
                        gray, width, height, column, top, last, gap)) continue;
                int spine = 0;
                for (int y = top; y <= last; y++)
                    if (Math.abs((bottom - y) / gap - Math.round((bottom - y) / gap)) > .16f
                            && (gray[y * width + column] & 255) < 160) spine++;
                if (spine < gap * 1.4f) continue;
                digitLeft = Math.round(column - gap * 1.4f);
            }
            int left = Math.round(clefRight + gap * .2f),
                    right = Math.round(digitLeft - gap * .15f);
            if (right - left + 1 < gap * .4f) continue;
            int threshold = BeamInkThreshold.at(gray, width, height, column, top, last, gap);
            int ink = 0, total = 0;
            for (int y = Math.max(0, Math.round(top - gap));
                    y <= Math.min(height - 1, Math.round(bottom + gap * .6f));
                    y++) {
                float phase = (bottom - y) / gap;
                if (y >= top - gap * .15f
                        && y <= bottom + gap * .15f
                        && Math.abs(phase - Math.round(phase)) < .16f) continue;
                for (int x = Math.max(0, left); x <= Math.min(width - 1, right); x++) {
                    total++;
                    if ((gray[y * width + x] & 255) < threshold) ink++;
                }
            }
            if (total > gap * gap && ink <= Math.max(2, total * .01f)) return true;
        }
        return false;
    }

    /** Two aligned numeral slots can leave a visibly blank key corridor after the clef. */
    static boolean matchesWideMeter(byte[] labels, byte[] gray, int width, int height,
            float clefRight, float firstHead, float staffBottom, float gap) {
        if (gray == null || labels == null || labels.length != gray.length
                || gray.length != (long) width * height || gap < 7 || gap > 32
                || !Float.isFinite(firstHead) || firstHead - clefRight < gap * 4) return false;
        int left = Math.round(clefRight + gap * .15f);
        int right = Math.round(Math.min(firstHead - gap * .7f, clefRight + gap * 4.25f));
        int top = Math.round(staffBottom - gap * 4);
        int middle = Math.round(staffBottom - gap * 2), bottom = Math.round(staffBottom);
        if (left < 0 || right >= width || top < gap || bottom + gap >= height || right <= left)
            return false;
        int upperLeft = right + 1, upperRight = -1, lowerLeft = right + 1, lowerRight = -1;
        int upperInk = 0, lowerInk = 0;
        for (int y = top; y <= bottom; y++) {
            if (staffRule(staffBottom, gap, y)) continue;
            for (int x = left; x <= right; x++) {
                int pixel = y * width + x;
                if (labels[pixel] != OmrMeasurePostProcessor.SYMBOL || (gray[pixel] & 255) >= 155)
                    continue;
                if (y < middle) {
                    upperLeft = Math.min(upperLeft, x);
                    upperRight = Math.max(upperRight, x);
                    upperInk++;
                } else {
                    lowerLeft = Math.min(lowerLeft, x);
                    lowerRight = Math.max(lowerRight, x);
                    lowerInk++;
                }
            }
        }
        int upperSpan = upperRight - upperLeft + 1, lowerSpan = lowerRight - lowerLeft + 1;
        if (upperSpan < gap * 1.85f || upperSpan > gap * 3
                || lowerSpan < gap * .8f || lowerSpan > gap * 1.7f
                || upperSpan < lowerSpan * 1.45f
                || Math.abs(upperLeft + upperRight - lowerLeft - lowerRight) > gap * .6f
                || upperInk < gap * gap * .95f || lowerInk < gap * gap * .55f
                || upperLeft - left < gap * .50f || upperRight >= right - gap * .15f) return false;
        if (!WholeHeadPocketEvidence.hasEnclosedOpening(gray, width, height,
                lowerLeft, middle + 1, lowerRight, bottom, (lowerLeft + lowerRight) * .5f,
                (middle + bottom) * .5f, gap, Math.round(gap * gap * .045f), 3)) return false;

        // Keep column extrema of both slots even when they differ; real key ink cannot be
        // hidden by using only the inset lower numeral as the meter's left edge.
        int blankRight = Math.min(upperLeft, lowerLeft) - Math.max(2, Math.round(gap * .13f));
        int ink = 0;
        for (int y = Math.round(top - gap * .75f); y <= Math.round(bottom + gap * .55f); y++) {
            if (staffRule(staffBottom, gap, y)) continue;
            for (int x = left; x <= blankRight; x++)
                if ((gray[y * width + x] & 255) < 150 && ++ink > 4) return false;
        }
        return true;
    }

    private static boolean staffRule(float bottom, float gap, int y) {
        float phase = (bottom - y) / gap;
        int line = Math.round(phase);
        return line >= 0 && line <= 4 && Math.abs(phase - line) <= .19f;
    }

    /** Open fours have a left arm falling away from a stable right spine,
     * followed by a full crossbar. Both stacked copies must prove that shape. */
    private static int openFourPairLeft(
            byte[] p, int width, int height, int column, int top, float gap) {
        int pairLeft = -1;
        for (int digit = 0; digit < 2; digit++) {
            float origin = top + digit * gap * 2;
            int threshold =
                    BeamInkThreshold.at(
                            p,
                            width,
                            height,
                            column,
                            Math.round(origin),
                            Math.round(origin + gap * 2),
                            gap);
            int left = Math.max(0, Math.round(column - gap * 1.5f)),
                    right = Math.min(width - 1, Math.round(column + gap * .5f));
            int rows = 0, minLeft = width;
            float firstLeft = 0, lastLeft = 0, firstRight = 0, lastRight = 0;
            for (int y = Math.max(0, Math.round(origin + gap * .55f));
                    y <= Math.min(height - 1, Math.round(origin + gap * 1.35f));
                    y++) {
                int a = -1, b = -1, c = -1, d = -1;
                for (int x = left; x <= right; x++) {
                    if ((p[y * width + x] & 255) >= threshold) continue;
                    int start = x;
                    while (x + 1 <= right && (p[y * width + x + 1] & 255) < threshold) x++;
                    if (a < 0) {
                        a = start;
                        b = x;
                    } else if (c < 0) {
                        c = start;
                        d = x;
                    } else {
                        a = -1;
                        break;
                    }
                }
                if (a < 0
                        || c < 0
                        || a == left
                        || d == right
                        || b - a + 1 < gap * .2f
                        || b - a + 1 > gap * .8f
                        || d - c + 1 < gap * .15f
                        || d - c + 1 > gap * .8f
                        || c - b < gap * .15f
                        || Math.abs((c + d) * .5f - column) > gap * .35f) continue;
                float lc = (a + b) * .5f, rc = (c + d) * .5f;
                if (rows == 0) {
                    firstLeft = lc;
                    firstRight = rc;
                }
                lastLeft = lc;
                lastRight = rc;
                rows++;
                minLeft = Math.min(minLeft, a);
            }
            if (rows < Math.max(3, Math.round(gap * .3f))
                    || firstLeft - lastLeft < gap * .16f
                    || Math.abs(firstRight - lastRight) > gap * .3f) return -1;
            boolean crossbar = false;
            for (int y = Math.max(0, Math.round(origin + gap * 1.35f));
                    y <= Math.min(height - 1, Math.round(origin + gap * 1.65f));
                    y++) {
                int ink = 0, total = 0;
                for (int x = minLeft; x <= Math.round(lastRight + gap * .15f); x++) {
                    total++;
                    if ((p[y * width + x] & 255) < threshold) ink++;
                }
                if (total >= gap * 1.05f && ink >= total * .9f) crossbar = true;
            }
            if (!crossbar || pairLeft >= 0 && Math.abs(minLeft - pairLeft) > gap * .3f) return -1;
            pairLeft = pairLeft < 0 ? minLeft : Math.min(pairLeft, minLeft);
        }
        return pairLeft;
    }
}
