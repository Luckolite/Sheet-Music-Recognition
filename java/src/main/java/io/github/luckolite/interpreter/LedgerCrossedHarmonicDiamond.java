// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** A ledger can flood the counter of a touching diamond but must leave its two tapered shoulders. */
final class LedgerCrossedHarmonicDiamond {
    private LedgerCrossedHarmonicDiamond() {}

    static boolean proved(byte[] labels, byte[] gray, int w, int h,
            float x, float y, float gap, int shaft, int direction) {
        if (labels == null || gray == null || labels.length != (long) w * h || gray.length != labels.length
                || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(gap)
                || gap < 10 || gap > 26 || (direction != 1 && direction != -1)) return false;
        int cx = Math.round(x), cy = Math.round(y);
        int radius = Math.round(gap * 1.03f), rise = Math.round(gap * .8f);
        if (cx - radius < 0 || cx + radius >= w || cy - rise < 0 || cy + rise >= h
                || (shaft - cx) * direction < gap * .32f
                || (shaft - cx) * direction > gap * .75f) return false;
        int limit = Math.round(gap * .3f);
        int first = -1, last = -1, count = 0;
        for (int yy = cy - limit; yy <= cy + limit; yy++) {
            int dark = 0;
            for (int xx = cx - radius; xx <= cx + radius; xx++)
                if (ink(gray, w, xx, yy)) dark++;
            if (dark < radius * 1.65f) continue;
            if (first < 0) first = yy;
            if (last >= 0 && yy != last + 1) return false;
            last = yy;
            count++;
        }
        if (count < 2 || count > gap * .4f || Math.abs((first + last) * .5f - cy) > gap * .22f)
            return false;
        int omit = Math.max(2, Math.round(gap * .14f));
        int left = direction == -1 ? shaft + omit : cx - radius;
        int right = direction == -1 ? cx + radius : shaft - omit;
        if (left >= right) return false;
        return shoulder(labels, gray, w, cx, cy - rise, first - 1, 1, left, right, gap)
                && shoulder(labels, gray, w, cx, cy + rise, last + 1, -1, left, right, gap);
    }

    private static boolean shoulder(byte[] labels, byte[] gray, int w, int cx,
            int start, int end, int dy, int left, int right, float gap) {
        int rows = 0, widest = 0, tip = 0, symbol = 0, area = 0, prior = 0;
        for (int y = start; dy * (end - y) >= 0; y += dy) {
            int span = 0, lowest = right, highest = left;
            for (int x = left; x <= right; x++) {
                if (!ink(gray, w, x, y)) continue;
                lowest = Math.min(lowest, x);
                highest = Math.max(highest, x);
                span++;
                if (labels[y * w + x] == OmrMeasurePostProcessor.SYMBOL) symbol++;
            }
            if (span == 0 && rows == 0) continue;
            if (span == 0 || span < prior - 2
                    || highest - lowest + 1 > span + Math.max(3, gap * .32f)) return false;
            if (rows == 0) {
                tip = span;
                if (Math.abs((lowest + highest) * .5f - cx) > gap * .23f) return false;
            }
            rows++;
            prior = span;
            widest = Math.max(widest, span);
            area += span;
        }
        return rows >= Math.max(4, gap * .27f) && tip <= gap * .29f
                && widest >= gap * .55f && widest >= tip + gap * .39f
                && symbol >= area * .26f;
    }

    private static boolean ink(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 255) < 150;
    }
}
