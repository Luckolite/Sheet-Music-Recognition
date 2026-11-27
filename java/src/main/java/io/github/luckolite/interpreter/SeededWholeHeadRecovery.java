// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class SeededWholeHeadRecovery {
    record Head(int left, int top, int right, int bottom, int area, float x, float y) {}

    /** A displaced ruled whole head can share one semantic blob with three stacked hollows. */
    static List<Head> displacedStack(
            byte[] gray, int width, int height, float gap,
            int left, int top, int right, int bottom) {
        float span = right - left + 1, tall = bottom - top + 1;
        if (gray == null || !Float.isFinite(gap) || gap < 9 || span < gap * 2.25f || span > gap * 3.65f
                || tall < gap * 1.55f || tall > gap * 3.45f) return List.of();
        int l = Math.max(0, left - Math.round(gap * .45f));
        int t = Math.max(0, top - Math.round(gap * 1.4f));
        int r = Math.min(width, right + Math.round(gap * .45f) + 1);
        int b = Math.min(height, bottom + Math.round(gap * .45f) + 1);
        var openings = apertures(gray, width, l, t, r, b, gap);
        var full = openings.stream().filter(p -> p.height() >= gap * .53f)
                .sorted(Comparator.comparingDouble(Pocket::y)).toList();
        List<Head> result = List.of();
        for (int i = 0; i + 2 < full.size(); i++) {
            var a = full.get(i);
            var c = full.get(i + 1);
            var e = full.get(i + 2);
            if (Math.abs(a.x() - c.x()) > gap * .23f || Math.abs(a.x() - e.x()) > gap * .23f
                    || Math.abs(c.y() - a.y() - gap) > gap * .19f
                    || Math.abs(e.y() - c.y() - gap) > gap * .19f) continue;
            float ruledY = (c.y() + e.y()) * .5f;
            Head displaced = ruledHead(gray, width, height, gap, openings, c.x(), ruledY);
            if (displaced == null) continue;
            Head upper = recover(gray, width, height, gap, a.x(), a.y());
            Head middle = recover(gray, width, height, gap, c.x(), c.y());
            Head lower = recover(gray, width, height, gap, e.x(), e.y());
            if (upper == null || middle == null || lower == null || !result.isEmpty())
                return List.of();
            result = List.of(lower, displaced, middle, upper);
        }
        return result;
    }

    private record Pocket(int left, int top, int right, int bottom) {
        float x() { return (left + right) * .5f; }
        float y() { return (top + bottom) * .5f; }
        int height() { return bottom - top + 1; }
    }

    private static List<Pocket> apertures(
            byte[] gray, int width, int left, int top, int right, int bottom, float gap) {
        int w = right - left, h = bottom - top;
        if (w < 1 || h < 1 || w > 150 || h > 160) return List.of();
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h], steps = {-1, 1, -w, w};
        var result = new ArrayList<Pocket>();
        for (int seed = 0; seed < seen.length; seed++) {
            if (seen[seed] || (gray[(top + seed / w) * width + left + seed % w] & 255) < 205)
                continue;
            int read = 0, count = 1;
            queue[0] = seed;
            seen[seed] = true;
            int x0 = w, y0 = h, x1 = 0, y1 = 0;
            while (read < count) {
                int at = queue[read++], x = at % w, y = at / w;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
                for (int step : steps) {
                    int next = at + step;
                    if (next < 0 || next >= seen.length || seen[next]
                            || Math.abs(next % w - x) + Math.abs(next / w - y) != 1
                            || (gray[(top + next / w) * width + left + next % w] & 255) < 205)
                        continue;
                    seen[next] = true;
                    queue[count++] = next;
                }
            }
            int apertureWidth = x1 - x0 + 1, apertureHeight = y1 - y0 + 1;
            if (x0 > 0 && x1 < w - 1 && y0 > 0 && y1 < h - 1
                    && apertureWidth >= gap * .35f && apertureWidth <= gap * .8f
                    && apertureHeight >= gap * .2f && apertureHeight <= gap * .95f
                    && count >= apertureWidth * apertureHeight * .55f)
                result.add(new Pocket(left + x0, top + y0, left + x1, top + y1));
        }
        return result;
    }

    private static Head ruledHead(
            byte[] gray, int width, int height, float gap, List<Pocket> pockets,
            float stackX, float y) {
        Head result = null;
        for (var up : pockets) {
            float dx = stackX - up.x();
            if (dx < gap * .95f || dx > gap * 1.75f || up.height() > gap * .48f
                    || up.bottom() >= y || y - up.bottom() > gap * .28f) continue;
            for (var down : pockets) {
                if (down.height() > gap * .48f || down.top() <= y || down.top() - y > gap * .28f
                        || Math.abs(up.x() - down.x()) > gap * .18f) continue;
                float x = (up.x() + down.x()) * .5f;
                int l = Math.round(x - gap * .81f), r = Math.round(x + gap * .81f);
                int t = Math.round(y - gap * .52f), b = Math.round(y + gap * .52f);
                if (!WholeHeadPocketEvidence.proved(gray, width, height, l, t, r, b, gap))
                    continue;
                int area = 0;
                for (int yy = t; yy <= b; yy++)
                    for (int xx = l; xx <= r; xx++)
                        if ((gray[yy * width + xx] & 255) < 170) area++;
                if (result != null) return null;
                result = new Head(l, t, r, b, area, x, y);
            }
        }
        return result;
    }


    /** Recover a symbol-labelled extension of a column with two labelled whole heads. */
    static Head wholeColumnEnd(byte[] labels, byte[] gray, byte[] raw, int width, int height,
            float gap, float staffTop, float staffBottom, float x, float y, int direction) {
        if (gray == null || !(gap >= 10 && gap <= 28)) return null;
        float endY = y + direction * gap, otherY = y - direction * gap;
        if (Math.min(endY, otherY) < staffTop + gap * .25f
                || Math.max(endY, otherY) > staffBottom - gap * .25f) return null;
        int xc = Math.round(x), dx = Math.round(gap * .64f), dy = Math.round(gap * .39f);
        int end = Math.round(endY), other = Math.round(otherY);
        if (xc - dx < 0 || xc + dx >= width || Math.min(end, other) - dy < 0
                || Math.max(end, other) + dy >= height) return null;
        int symbols = 0, endHeads = 0, otherHeads = 0;
        for (int v = -dy; v <= dy; v++)
            for (int u = -dx; u <= dx; u++) {
                byte label = labels[(end + v) * width + xc + u];
                if (label == OmrMeasurePostProcessor.SYMBOL) symbols++;
                if (label == OmrMeasurePostProcessor.NOTEHEAD) endHeads++;
                if (labels[(other + v) * width + xc + u] == OmrMeasurePostProcessor.NOTEHEAD)
                    otherHeads++;
            }
        if (symbols < gap * gap * .45f || endHeads > gap * gap * .12f
                || otherHeads < gap * gap * .28f) return null;
        Head endHead = alignedWholeColumn(gray, width, height, gap, x, y, direction);
        if (endHead == null || raw == null || raw == gray) return endHead;
        Head original = alignedWholeColumn(raw, width, height, gap, x, y, direction);
        return original != null && Math.abs(endHead.x() - original.x()) <= gap * .15f
                && Math.abs(endHead.y() - original.y()) <= gap * .15f ? endHead : null;
    }

    private static Head alignedWholeColumn(byte[] gray, int width, int height,
            float gap, float x, float y, int direction) {
        Head stemless = recover(gray, width, height, gap, x, y);
        if (stemless == null) return null;
        Head other = recover(gray, width, height, gap, x, y - direction * gap);
        if (!alignedAdjacent(stemless, other, gap, -direction)) return null;
        Head end = recover(gray, width, height, gap, x, y + direction * gap);
        return alignedAdjacent(stemless, end, gap, direction) ? end : null;
    }

    private static boolean alignedAdjacent(Head center, Head other, float gap, int direction) {
        return other != null && Math.abs(center.x() - other.x()) <= gap * .20f
                && Math.abs(other.y() - center.y() - direction * gap) <= gap * .18f;
    }

    static Head recover(byte[] gray, int width, int height, float gap, float seedX, float seedY) {
        if (gray == null
                || width < 1
                || height < 1
                || gray.length != (long) width * height
                || !Float.isFinite(gap)
                || gap < 6) return null;
        int l = Math.max(0, Math.round(seedX - gap * 1.4f)),
                r = Math.min(width - 1, Math.round(seedX + gap * 1.4f));
        int t = Math.max(0, Math.round(seedY - gap)),
                b = Math.min(height - 1, Math.round(seedY + gap));
        int w = r - l + 1, h = b - t + 1;
        if (w < 1 || h < 1) return null;
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h], steps = {-1, 1, -w, w};
        for (int seed = 0; seed < w * h; seed++) {
            if (seen[seed] || (gray[(t + seed / w) * width + l + seed % w] & 255) < 205) continue;
            int read = 0, end = 1;
            queue[0] = seed;
            seen[seed] = true;
            boolean edge = false;
            int minX = w, maxX = 0, minY = h, maxY = 0;
            while (read < end) {
                int p = queue[read++], x = p % w, y = p / w;
                edge |= x == 0 || x == w - 1 || y == 0 || y == h - 1;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                for (int delta : steps) {
                    int n = p + delta;
                    if (n < 0
                            || n >= w * h
                            || seen[n]
                            || Math.abs(n % w - x) + Math.abs(n / w - y) != 1) continue;
                    if ((gray[(t + n / w) * width + l + n % w] & 255) >= 205) {
                        seen[n] = true;
                        queue[end++] = n;
                    }
                }
            }
            if (edge || end < gap * gap * .1f) continue;
            float cx = l + (minX + maxX) * .5f, cy = t + (minY + maxY) * .5f;
            if (Math.abs(cx - seedX) > gap * .45f || Math.abs(cy - seedY) > gap * .35f) continue;
            int left = Math.round(cx - gap * .85f),
                    right = Math.round(cx + gap * .85f),
                    top = Math.round(cy - gap * .55f),
                    bottom = Math.round(cy + gap * .55f);
            if (!WholeHeadPocketEvidence.proved(gray, width, height, left, top, right, bottom, gap))
                continue;
            boolean sides = true;
            for (int side : new int[] {-1, 1}) {
                int ink = 0, total = 0;
                for (int y = Math.round(cy - gap * .25f); y <= Math.round(cy + gap * .25f); y++)
                    for (int dx = Math.round(gap * .4f); dx <= Math.round(gap * .8f); dx++) {
                        int x = Math.round(cx) + side * dx;
                        total++;
                        if ((gray[y * width + x] & 255) < 170) ink++;
                    }
                if (ink < total * .6f) sides = false;
            }
            if (!sides) continue;
            int area = 0;
            for (int y = top; y <= bottom; y++)
                for (int x = left; x <= right; x++) if ((gray[y * width + x] & 255) < 170) area++;
            return new Head(left, top, right, bottom, area, cx, cy);
        }
        return null;
    }
}
