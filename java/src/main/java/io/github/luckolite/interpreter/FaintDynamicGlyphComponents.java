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
            if ((gray[p] & 255) >= threshold || seen[p]) continue;
            int read = 0, queued = 1, count = 0, dark = 0;
            queue[0] = p;
            seen[p] = true;
            int top = p / width, bottom = top, left = width, right = 0;
            while (read < queued) {
                int seed = queue[read++], y = seed / width, row = y * width;
                int first = seed, last = seed;
                int tone = gray[seed] & 255;
                if (tone < 145) dark++;
                else if (!narrow && tone < 185) hasFaintInk = true;
                while (first > row && !seen[first - 1]) {
                    tone = gray[first - 1] & 255;
                    if (tone >= threshold) break;
                    first--;
                    if (tone < 145) dark++;
                    else if (!narrow && tone < 185) hasFaintInk = true;
                }
                while (last < row + width - 1 && !seen[last + 1]) {
                    tone = gray[last + 1] & 255;
                    if (tone >= threshold) break;
                    last++;
                    if (tone < 145) dark++;
                    else if (!narrow && tone < 185) hasFaintInk = true;
                }
                Arrays.fill(seen, first, last + 1, true);
                left = Math.min(left, first - row);
                right = Math.max(right, last - row);
                bottom = Math.max(bottom, y);
                count += last - first + 1;
                int neighborFirst = Math.max(row, first - 1);
                int neighborLast = Math.min(row + width - 1, last + 1);
                for (int step = -1; step <= 1; step += 2) {
                    if (y + step < 0 || y + step >= height) continue;
                    int end = neighborLast + step * width;
                    for (int next = neighborFirst + step * width; next <= end; next++) {
                        if (seen[next] || (gray[next] & 255) >= threshold) continue;
                        seen[next] = true;
                        queue[queued++] = next;
                        while (next < end && !seen[next + 1] && (gray[next + 1] & 255) < threshold)
                            next++;
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
