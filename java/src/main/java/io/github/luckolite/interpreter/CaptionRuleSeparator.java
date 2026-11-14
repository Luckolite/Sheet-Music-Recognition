// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Observed full-width staff band and paper enclosure; original OCR geometry is never rewritten. */
final class CaptionRuleSeparator {
    static float bodyTop(
            byte[] gray,
            int w,
            int h,
            PlayingTechniqueDetector.Word word,
            float bottom,
            float gap) {
        if (gray == null
                || word == null
                || w < 1
                || h < 1
                || gray.length != (long) w * h
                || !Float.isFinite(bottom)
                || !Float.isFinite(gap)
                || gap < 6
                || !Float.isFinite(word.left())
                || !Float.isFinite(word.right())
                || !Float.isFinite(word.top())
                || !Float.isFinite(word.bottom())) return Float.NaN;
        int l = (int) Math.floor(word.left() * w), r = (int) Math.ceil(word.right() * w) - 1;
        int t = (int) Math.floor(word.top() * h), b = (int) Math.ceil(word.bottom() * h) - 1;
        if (l < 0
                || r >= w
                || t < 0
                || b >= h
                || l >= r
                || t >= b
                || (long) (r - l + 1) * (b - t + 1) > 65536
                || t < Math.floor(bottom - .3f * gap)
                || t > bottom
                || b <= bottom) return Float.NaN;
        int separator = -1, last = Math.min(b, (int) Math.ceil(bottom + .3f * gap));
        boolean core = false;
        for (int y = t; y <= last; y++) {
            int start = y * w + l, end = y * w + r;
            int category = railTone(gray[start]);
            for (int at = start + 1; at <= end; at++)
                if (railTone(gray[at]) != category) return Float.NaN;
            if (category == 2) {
                if (core && y > bottom) separator = y;
                break;
            }
            // Sparse strong ink cannot borrow a full fringe row as its own staff role.
            if (category == 0) core = true;
        }
        if (separator < 0) return Float.NaN;
        int bodyStart = separator + 1;
        for (int row = bodyStart * w, end = b * w; row < end; row += w)
            if (gray[row + l] != (byte) 255 || gray[row + r] != (byte) 255) return Float.NaN;
        for (int at = b * w + l, end = b * w + r; at <= end; at++)
            if (gray[at] != (byte) 255) return Float.NaN;

        int first = -1;
        boolean strongBody = false;
        for (int y = bodyStart, row = bodyStart * w; y < b && !strongBody; y++, row += w) {
            for (int at = row + l + 1, end = row + r; at < end; at++) {
                int value = gray[at] & 255;
                if (value == 255) continue;
                if (first < 0) first = y;
                if (value < 145) {
                    strongBody = true;
                    break;
                }
            }
        }
        if (!strongBody) return Float.NaN;
        // Both original span edges must independently register the same five rails.
        if (!registered(gray, w, h, l, bottom, gap) || !registered(gray, w, h, r, bottom, gap))
            return Float.NaN;
        return first;
    }

    private static boolean registered(byte[] gray, int width, int height, int x, float bottom, float gap) {
        var rules = PrintedDirectionStaff.printedRules(gray, width, height, x, bottom, gap);
        return rules != null
                && Math.abs(rules[0] - bottom) <= .2f * gap
                && Math.abs(rules[1] - gap) <= .08f * gap;
    }

    private static int railTone(byte pixel) {
        int value = pixel & 255;
        if (value == 255) return 2;
        return value < 145 ? 0 : 1;
    }
}
