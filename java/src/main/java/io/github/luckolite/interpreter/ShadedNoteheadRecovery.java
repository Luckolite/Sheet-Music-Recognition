// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Recover a compact, curved grey fill even when its dark outline has scan gaps. */
final class ShadedNoteheadRecovery {
    private ShadedNoteheadRecovery() {}

    record Head(int left, int top, int right, int bottom, float centerX, float centerY) {}

    static List<Head> find(
            byte[] labels, byte[] gray, int width, int height, float gap, int top, int bottom) {
        List<Head> result = new ArrayList<>();
        if (gray == null
                || labels == null
                || width < 3
                || height < 3
                || gray.length != (long) width * height
                || labels.length != gray.length
                || gap < 6
                || !Float.isFinite(gap)) return result;
        top = Math.max(1, top);
        bottom = Math.min(height - 2, bottom);
        if (bottom <= top) return result;
        int size = width * (bottom - top + 1);
        byte[] core = null;
        int highest = 0;
        for (int y = top; y <= bottom; y++) {
            int row = y * width, band = (y - top) * width;
            for (int x = 1; x < width - 1; x++) {
                int at = row + x;
                int center = gray[at] & 255;
                if (center < 105 || center > 205) continue;
                int left = gray[at - 1] & 255, right = gray[at + 1] & 255;
                int above = gray[at - width] & 255, below = gray[at + width] & 255;
                int darkest = Math.min(center, Math.min(Math.min(left, right), Math.min(above, below)));
                int lightest = Math.max(Math.max(left, right), Math.max(above, below));
                if (darkest < 105 || lightest > 205) continue;
                int level = darkest >= 155 ? 3 : darkest >= 130 ? 2 : 1;
                if (core == null) core = new byte[size];
                core[band + x] = (byte) level;
                highest = Math.max(highest, level);
            }
        }
        if (core == null) return result;
        var queue = new ShadedComponentRuns(Math.min(size, 256));
        int[] paper = new int[18];
        if (highest == 3)
            result = find(labels, gray, width, height, gap, top, bottom, 155, 3, core, queue, paper);
        for (int level = Math.min(2, highest); level >= 1; level--)
            for (Head candidate : find(labels, gray, width, height, gap, top, bottom,
                    level == 2 ? 130 : 105, level, core, queue, paper)) {
                boolean duplicate = false;
                for (Head existing : result)
                    if (Math.abs(existing.centerX - candidate.centerX) < gap * .8f
                            && Math.abs(existing.centerY - candidate.centerY) < gap * .65f) {
                        duplicate = true;
                        break;
                    }
                if (!duplicate) result.add(candidate);
            }
        return result;
    }

    private static List<Head> find(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float gap,
            int top,
            int bottom,
            int minimum, int level, byte[] core, ShadedComponentRuns queue, int[] paper) {
        List<Head> result = new ArrayList<>();
        int size = core.length;
        byte visited = (byte) (level - 1);
        for (int seed = 0; seed < size; seed++) {
            if (core[seed] != level) continue;
            int end = queue.enqueue(core, seed, level, visited, 0);
            int area = 0, left = width, right = 0;
            int upper = top + seed / width, lower = upper;
            for (int read = 0; read < end; read += 2) {
                int start = queue.queue[read], finish = queue.queue[read + 1];
                int row = start / width, rowStart = row * width;
                left = Math.min(left, start - rowStart);
                right = Math.max(right, finish - rowStart);
                lower = Math.max(lower, top + row);
                area += finish - start + 1;
                for (int step = -width; step <= width; step += 2 * width) {
                    int next = start + step, last = finish + step;
                    if (next < 0 || last >= size) continue;
                    for (; next <= last; next++) {
                        if (core[next] != level) continue;
                        end = queue.enqueue(core, next, level, visited, end);
                        next = queue.queue[end - 1];
                    }
                }
            }
            int w = right - left + 1, h = lower - upper + 1;
            if (w < gap * .55f
                    || w > gap * 1.35f
                    || h < gap * .35f
                    || h > gap * 1.05f
                    || w < h * .65f
                    || w > h * 2.4f
                    || area < w * h * .65f
                    || upper == top
                    || lower == bottom) continue;
            int[] rowLeft = new int[h], rowRight = new int[h], values = new int[area];
            Arrays.fill(rowLeft, width);
            int value = 0, source = top * width;
            for (int i = 0; i < end; i += 2) {
                int start = queue.queue[i], finish = queue.queue[i + 1];
                int row = start / width, rowStart = row * width, index = top + row - upper;
                rowLeft[index] = Math.min(rowLeft[index], start - rowStart);
                rowRight[index] = Math.max(rowRight[index], finish - rowStart);
                for (int at = start; at <= finish; at++) values[value++] = gray[source + at] & 255;
            }
            int middle = h / 2, edge = Math.max(0, Math.round(h * .12f));
            float bow =
                    Math.max(
                            (rowLeft[edge] + rowLeft[h - 1 - edge]) * .5f - rowLeft[middle],
                            rowRight[middle] - (rowRight[edge] + rowRight[h - 1 - edge]) * .5f);
            // Integer edge coordinates average to half-pixel bow measurements.
            float minimumBow = Math.max(.5f, gap * .025f);
            // A staff-rule remnant can flatten the exact middle scan row. A
            // gray oval may instead show its bow on two adjacent inner rows;
            // never accept a lone raster nick or an uncurved rectangle.
            if (bow < minimumBow)
                for (int row = Math.max(1, h / 3); row < Math.min(h - 2, h * 2 / 3) && bow < minimumBow; row++) {
                    float leftBow =
                            (rowLeft[edge] + rowLeft[h - 1 - edge]) * .5f
                                    - Math.max(rowLeft[row], rowLeft[row + 1]);
                    float rightBow =
                            Math.min(rowRight[row], rowRight[row + 1])
                                    - (rowRight[edge] + rowRight[h - 1 - edge]) * .5f;
                    bow = Math.max(bow, Math.max(leftBow, rightBow));
                }
            if (bow < minimumBow) continue;
            Arrays.sort(values);
            int fill = values[area / 2];
            int count = 0;
            float cx = (left + right) * .5f, cy = (upper + lower) * .5f;
            for (int side : new int[] {-1, 1})
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = 0; dx < 3; dx++) {
                        int x = Math.round(cx + side * gap * (1.3f + dx * .2f)),
                                y = Math.round(cy + dy * gap * .2f);
                        if (x >= 0 && x < width && y >= 0 && y < height)
                            paper[count++] = gray[y * width + x] & 255;
                    }
            if (count < 12) continue;
            Arrays.sort(paper, 0, count);
            int background = paper[count * 3 / 4];
            if (minimum == 105) {
                if (fill > 150 || background < 190 || background - fill < 65)
                    continue;
            } else if (minimum == 130) {
                if (fill > 170 || background < 190 || background - fill < 50)
                    continue;
            } else if (background < 205
                    || background - fill < (background < 220 ? 50 : 30))
                continue;
            int down = stem(labels, gray, width, height, left - 3, cy, gap, 1);
            int up = stem(labels, gray, width, height, right + 3, cy, gap, -1);
            if (down < 0 && up < 0) continue;
            int headLeft = Math.max(0, down < 0 ? left - 3 : Math.min(left - 3, down));
            int headRight = Math.min(width - 1, up < 0 ? right + 3 : Math.max(right + 3, up));
            result.add(
                    new Head(
                            headLeft,
                            Math.max(0, upper - 1),
                            headRight,
                            Math.min(height - 1, lower + 1),
                            Math.max(headLeft, Math.min(headRight, cx)),
                            cy));
        }
        return result;
    }

    private static int stem(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int edge,
            float cy,
            float gap,
            int direction) {
        int center = Math.round(cy), first = Math.round(gap * .75f);
        float limit = gap * 2.3f;
        int last = (int) limit;
        // Int-to-float comparison can include additional distances for very large crops.
        while (last < Integer.MAX_VALUE && last + 1 <= limit) last++;
        long startY = (long) center + direction * (long) first;
        long endY = (long) center + direction * (long) last;
        if (first > last || startY < 0 || startY >= height || endY < 0 || endY >= height)
            return -1;
        int total = last - first + 1, stride = direction * width;
        float inkNeeded = total * .85f, semanticNeeded = total * .5f;
        for (int x = Math.max(0, edge - 3); x <= Math.min(width - 1, edge + 3); x++) {
            int ink = 0, semantic = 0;
            for (int remaining = total - 1, at = (int) startY * width + x;
                    remaining >= 0; remaining--, at += stride) {
                if ((gray[at] & 255) < 160) ink++;
                else if (ink + remaining < inkNeeded) break;
                if (labels[at] == OmrMeasurePostProcessor.STEM_OR_REST) semantic++;
                else if (semantic + remaining < semanticNeeded) break;
                if (ink >= inkNeeded && semantic >= semanticNeeded) return x;
            }
        }
        return -1;
    }
}
