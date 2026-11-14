// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Propose predominantly faded glyphs; ordinary dark candidates keep their existing bounds. */
final class FaintDynamicGlyphComponents {
    record Bounds(int left, int top, int right, int bottom, int pixels) {}

    static List<Bounds> find(byte[] gray, int width, int height, boolean[] seen, int[] queue) {
        var result = new ArrayList<Bounds>();
        boolean hasFaintInk = findAt(gray, width, height, seen, queue, 205, .2f, result);
        if (hasFaintInk) findAt(gray, width, height, seen, queue, 185, .65f, result);
        return result;
    }

    private static boolean findAt(
            byte[] gray,
            int width,
            int height,
            boolean[] seen,
            int[] queue,
            int threshold,
            float darkFraction,
            List<Bounds> result) {
        Arrays.fill(seen, false);
        boolean hasFaintInk = false;
        boolean narrow = threshold == 185;
        for (int p = 0; p < gray.length; p++) {
            if (seen[p] || (gray[p] & 255) >= threshold) continue;
            int read = 0, count = 1;
            queue[0] = p;
            seen[p] = true;
            int left = p % width, right = left, top = p / width, bottom = top;
            int dark = 0;
            while (read < count) {
                int at = queue[read++], x = at % width, y = at / width;
                left = Math.min(left, x);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
                int tone = gray[at] & 255;
                if (tone < 145) dark++;
                else if (!narrow && tone < 185) hasFaintInk = true;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                        int next = ny * width + nx;
                        if (!seen[next] && (gray[next] & 255) < threshold) {
                            seen[next] = true;
                            queue[count++] = next;
                        }
                    }
            }
            if (count >= 3 && dark < count * darkFraction
                    && (!narrow || !covered(result, left, top, right + 1, bottom + 1)))
                result.add(new Bounds(left, top, right + 1, bottom + 1, count));
        }
        return hasFaintInk;
    }

    private static boolean covered(List<Bounds> result, int left, int top, int right, int bottom) {
        for (int i = 0; i < result.size(); i++) {
            var prior = result.get(i);
            if (prior.left() <= left && prior.top() <= top
                    && prior.right() >= right && prior.bottom() >= bottom) return true;
        }
        return false;
    }
}
