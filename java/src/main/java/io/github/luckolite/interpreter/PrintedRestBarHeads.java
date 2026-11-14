// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.BitSet;

/** Excludes only a printed thick horizontal bar with two bilateral vertical caps. */
final class PrintedRestBarHeads {
    static byte[] withoutBars(byte[] labels, byte[] gray, int w, int h) {
        if (labels == null
                || gray == null
                || labels.length != (long) w * h
                || gray.length != labels.length) return labels;
        BitSet seen = null;
        int[] queue = null;
        byte[] result = labels;
        for (int origin = 0; origin < labels.length; origin++) {
            if (labels[origin] != 2 || (seen != null && seen.get(origin))) continue;
            if (seen == null) {
                seen = new BitSet(labels.length);
                queue = new int[Math.min(256, labels.length)];
            }
            int read = 0, size = 1, minX = origin % w, maxX = minX, minY = origin / w, maxY = minY;
            queue[0] = origin;
            seen.set(origin);
            while (read < size) {
                int at = queue[read++], x = at % w, y = at / w;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (queue.length < labels.length && queue.length - size < 4) {
                    int growth = Math.max(4, queue.length / 2);
                    queue = Arrays.copyOf(queue, queue.length + Math.min(growth, labels.length - queue.length));
                }
                if (x > 0) size = enqueue(labels, seen, queue, size, at - 1);
                if (x + 1 < w) size = enqueue(labels, seen, queue, size, at + 1);
                if (y > 0) size = enqueue(labels, seen, queue, size, at - w);
                if (y + 1 < h) size = enqueue(labels, seen, queue, size, at + w);
            }
            if (!proved(gray, w, h, minX, minY, maxX, maxY)
                    && !fragment(gray, w, h, minX, minY, maxX, maxY)) continue;
            if (result == labels) result = labels.clone();
            for (int i = 0; i < size; i++) result[queue[i]] = 0;
        }
        return result;
    }

    private static int enqueue(byte[] labels, BitSet seen, int[] queue, int size, int index) {
        if (labels[index] != 2 || seen.get(index)) return size;
        seen.set(index);
        queue[size] = index;
        return size + 1;
    }

    static boolean fragment(byte[] gray, int w, int h, int left, int top, int right, int bottom) {
        int x = (left + right) / 2, y = (top + bottom) / 2;
        int center = gray[y * w + x] & 255;
        if (center >= 150) return false;
        int limit =
                Math.min(
                        150,
                        BeamInkThreshold.at(
                                gray, w, h, x, top, bottom, Math.max(8, bottom - top + 1)));
        if (center >= limit) return false;
        int t = y, b = y;
        for (int at = y * w + x - w; t > 0 && y - t < 48; at -= w) {
            if ((gray[at] & 255) >= limit) break;
            t--;
        }
        for (int at = y * w + x + w; b < h - 1 && b - y < 48; at += w) {
            if ((gray[at] & 255) >= limit) break;
            b++;
        }
        int bh = b - t + 1;
        if (bh < 4 || bh > 48 || top < t - 1 || bottom > b + 1) return false;
        int l = x, r = x;
        while (l > 0 && x - l < bh * 12 && solidColumn(gray, w, l - 1, t, b, limit)) l--;
        while (r < w - 1 && r - x < bh * 12 && solidColumn(gray, w, r + 1, t, b, limit)) r++;
        if (left < l - 1 || right > r + 1 || r - l + 1 < bh * 3) return false;
        return proved(gray, w, h, l, t, r, b);
    }

    private static boolean solidColumn(byte[] gray, int w, int x, int top, int bottom, int limit) {
        return columnHasInk(gray, w, x, top, bottom, limit, (bottom - top + 1) * .85f);
    }

    static boolean proved(byte[] gray, int w, int h, int left, int top, int right, int bottom) {
        int bw = right - left + 1, bh = bottom - top + 1;
        if (bh < 4 || bh > 48 || bw < bh * 3 || bw > bh * 12 || top - bh < 0 || bottom + bh >= h)
            return false;
        int limit =
                Math.min(
                        150,
                        BeamInkThreshold.at(
                                gray, w, h, (left + right) / 2, top, bottom, Math.max(8, bh)));
        int columns = 0;
        float requiredColumns = bw * .8f;
        for (int x = left; x <= right; x++) {
            if (columnHasInk(gray, w, x, top, bottom, limit, bh * .5f)) {
                if (++columns >= requiredColumns) break;
            }
            if (columns + right - x < requiredColumns) return false;
        }
        int radius = Math.max(2, Math.round(bh * .15f));
        int near = Math.max(2, Math.round(bh * .18f));
        int far = Math.max(3, Math.round(bh * .48f));
        return cap(gray, w, left, top, bottom, limit, radius, near, far)
                && cap(gray, w, right, top, bottom, limit, radius, near, far);
    }

    private static boolean cap(byte[] gray, int w, int edge, int top, int bottom,
            int limit, int radius, int near, int far) {
        float required = (far - near + 1) * .7f;
        int right = Math.min(w - 1, edge + radius);
        for (int x = Math.max(0, edge - radius); x <= right; x++) {
            if (columnHasInk(gray, w, x, top - far, top - near, limit, required)
                    && columnHasInk(gray, w, x, bottom + near, bottom + far, limit, required))
                return true;
        }
        return false;
    }

    private static boolean columnHasInk(byte[] gray, int w, int x, int top, int bottom,
            int limit, float required) {
        int ink = 0, remaining = bottom - top + 1;
        for (int at = top * w + x; remaining > 0; at += w) {
            if ((gray[at] & 255) < limit && ++ink >= required) return true;
            if (ink + --remaining < required) return false;
        }
        return ink >= required;
    }
}
