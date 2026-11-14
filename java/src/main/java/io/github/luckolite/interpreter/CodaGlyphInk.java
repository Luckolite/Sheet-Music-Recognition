// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** A coda has a crossed ring enclosing four curved white quadrants. */
final class CodaGlyphInk {
    static boolean matches(
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            float gap) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || left < 0
                || top < 0
                || right >= width
                || bottom >= height
                || right <= left
                || bottom <= top
                || !Float.isFinite(gap)
                || gap < 5) return false;
        int w = right - left + 1, h = bottom - top + 1;
        if (w < gap
                || w > gap * 3.5f
                || h < gap * 1.6f
                || h > gap * 4.25f
                || w / (float) h < .65f
                || w / (float) h > 1.15f
                || (long) w * h > 65536) return false;
        // A coda must have both cross strokes before its enclosed paper is examined.
        if (!hasCrossStroke(gray, width, left, top, w, h, 1, width)
                || !hasCrossStroke(gray, width, left, top, h, w, width, 1)) return false;
        int stride = w + 2, firstRow = stride + 1;
        boolean[] paper = new boolean[stride * (h + 2)];
        for (int y = 0; y < h; y++) {
            int row = (top + y) * width + left, crop = firstRow + y * stride;
            for (int x = 0; x < w; x++) paper[crop + x] = (gray[row + x] & 255) >= 175;
        }
        int[] queue = new int[w * h];
        int holes = 0, quadrants = 0;
        for (int seed = firstRow; seed < firstRow + h * stride; seed++) {
            if (!paper[seed]) continue;
            int count = 1, take = 0, l = w, r = 0, t = seed / stride - 1, b = t;
            boolean border = false;
            int sx = 0, sy = 0;
            paper[seed] = false;
            queue[0] = seed;
            while (take < count) {
                int at = queue[take++], yy = at / stride;
                int x = at - yy * stride - 1, y = yy - 1;
                l = Math.min(l, x);
                r = Math.max(r, x);
                b = Math.max(b, y);
                sx += x;
                sy += y;
                border |= x == 0 || y == 0 || x == w - 1 || y == h - 1;
                int next = at - 1;
                if (paper[next]) {
                    paper[next] = false;
                    queue[count++] = next;
                }
                next = at + 1;
                if (paper[next]) {
                    paper[next] = false;
                    queue[count++] = next;
                }
                next = at - stride;
                if (paper[next]) {
                    paper[next] = false;
                    queue[count++] = next;
                }
                next = at + stride;
                if (paper[next]) {
                    paper[next] = false;
                    queue[count++] = next;
                }
            }
            if (border) continue;
            if (count < w * h * .015f) return false;
            if (++holes > 4
                    || count > w * h * .15f
                    || l < w * .15f
                    || r > w * .85f
                    || t < h * .15f
                    || b > h * .85f) return false;
            float fill = count / (float) ((r - l + 1) * (b - t + 1));
            if (fill < .35f || fill > .94f) return false;
            float cx = sx / (float) count, cy = sy / (float) count;
            if (Math.abs(cx - (w - 1) * .5f) < w * .035f
                    || Math.abs(cy - (h - 1) * .5f) < h * .035f) return false;
            int quadrant = (cx > (w - 1) * .5f ? 1 : 0) + (cy > (h - 1) * .5f ? 2 : 0);
            if ((quadrants & (1 << quadrant)) != 0) return false;
            quadrants |= 1 << quadrant;
        }
        if (holes != 4 || quadrants != 15) return false;
        return true;
    }

    private static boolean hasCrossStroke(
            byte[] gray, int width, int left, int top, int breadth, int length,
            int acrossStep, int alongStep) {
        int offset = top * width + left;
        int required = (int) Math.ceil(length * .85f);
        for (int across = Math.max(0, breadth / 2 - 2);
                across <= Math.min(breadth - 1, breadth / 2 + 2); across++) {
            int remaining = required, at = offset + across * acrossStep;
            for (int along = 0; along < length && remaining <= length - along;
                    along++, at += alongStep) {
                if ((gray[at] & 255) < 175 && --remaining == 0) return true;
            }
        }
        return false;
    }
}
