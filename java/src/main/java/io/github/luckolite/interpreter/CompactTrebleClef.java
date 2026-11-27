// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Confirms a compact printed G clef whose upper tip barely clears the staff. */
final class CompactTrebleClef {
    private CompactTrebleClef() {}

    static boolean matches(byte[] labels, byte[] gray, int width, int height,
            int left, int top, int right, int bottom, float staffBottom, float gap) {
        int w = right - left + 1, h = bottom - top + 1;
        if (gap < 8 || gap > 32 || gray == null || labels == null
                || left < 0 || right >= width || top < 0 || bottom >= height
                || w < gap * 1.5f || w > gap * 2.35f || h < gap * 4.75f || h > gap * 5.6f
                || top < staffBottom - gap * 4.4f || top > staffBottom - gap * 3.8f
                || bottom < staffBottom + gap * .6f || bottom > staffBottom + gap * 1.2f)
            return false;
        float[] a = PrintedDirectionStaff.printedRules(gray, width, height, left - gap, staffBottom, gap);
        float[] b = PrintedDirectionStaff.printedRules(gray, width, height, right + gap, staffBottom, gap);
        if (a == null || b == null || Math.abs(a[0] - b[0]) > gap * .2f
                || Math.abs(a[1] - b[1]) > gap * .07f || Math.abs(a[0] - staffBottom) > gap * .35f)
            return false;
        boolean[] seen = new boolean[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int at = (top + y) * width + left + x;
                if (labels[at] == OmrMeasurePostProcessor.NOTEHEAD && (gray[at] & 255) < 175)
                    return false;
                seen[y * w + x] = labels[at] == OmrMeasurePostProcessor.CLEF_OR_KEY && (gray[at] & 255) < 175;
            }
        int[] queue = new int[seen.length];
        int eyes = 0;
        float mid = (w - 1) * .5f;
        for (int origin = 0; origin < seen.length; origin++) {
            if (seen[origin]) continue;
            seen[origin] = true;
            queue[0] = origin;
            int count = 1, sumX = 0, sumY = 0, minY = h, maxY = -1;
            boolean open = false;
            for (int i = 0; i < count; i++) {
                int at = queue[i], x = at % w, y = at / w;
                sumX += x;
                sumY += y;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                open |= x == 0 || x == w - 1 || y == 0 || y == h - 1;
                if (x > 0) count = visit(seen, queue, count, at - 1);
                if (x + 1 < w) count = visit(seen, queue, count, at + 1);
                if (y > 0) count = visit(seen, queue, count, at - w);
                if (y + 1 < h) count = visit(seen, queue, count, at + w);
            }
            if (open || count < gap * gap * .16f || maxY - minY < gap * .55f) continue;
            float cx = sumX / (float) count, cy = top + sumY / (float) count;
            if (cx > mid && cy > staffBottom - gap * 3.7f && cy < staffBottom - gap * 2.8f)
                eyes |= 1;
            if (cy < staffBottom - gap * .45f && cy > staffBottom - gap * 1.35f) {
                if (cx < mid - gap * .12f && maxY - minY > gap * 1.25f) eyes |= 2;
                if (cx > mid + gap * .15f) eyes |= 4;
            }
        }
        return eyes == 7;
    }

    private static int visit(boolean[] seen, int[] queue, int count, int at) {
        if (seen[at]) return count;
        seen[at] = true;
        queue[count] = at;
        return count + 1;
    }
}
