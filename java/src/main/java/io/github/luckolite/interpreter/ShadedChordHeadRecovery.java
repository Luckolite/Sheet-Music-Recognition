// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Split a curved, uniformly shaded chord body using its independently printed lobes. */
final class ShadedChordHeadRecovery {
    private ShadedChordHeadRecovery() {}

    record Head(int left, int top, int right, int bottom, float centerX, float centerY) {}

    record Chord(int left, int top, int right, int bottom, List<Head> heads) {}

    static List<Chord> find(
            byte[] labels, byte[] gray, int width, int height, float gap, int top, int bottom) {
        List<Chord> result = new ArrayList<>();
        if (gray == null
                || labels == null
                || width < 3
                || height < 3
                || gray.length != (long) width * height
                || labels.length != gray.length
                || gap < 8
                || !Float.isFinite(gap)) return result;
        top = Math.max(1, top);
        bottom = Math.min(height - 2, bottom);
        if (bottom <= top) return result;
        int size = width * (bottom - top + 1);
        byte[] core = null;
        boolean hasStrictCore = false;
        for (int y = top; y <= bottom; y++) {
            int row = y * width, bandRow = (y - top) * width;
            for (int x = 1; x < width - 1; x++) {
                int p = row + x;
                int center = gray[p] & 255;
                if (center < 130 || center > 205) continue;
                int left = gray[p - 1] & 255, right = gray[p + 1] & 255;
                int above = gray[p - width] & 255, below = gray[p + width] & 255;
                int darkest = Math.min(center, Math.min(Math.min(left, right), Math.min(above, below)));
                int lightest = Math.max(Math.max(left, right), Math.max(above, below));
                if (darkest < 130 || lightest > 205) continue;
                if (core == null) core = new byte[size];
                if (darkest >= 155) {
                    core[bandRow + x] = 2;
                    hasStrictCore = true;
                } else {
                    core[bandRow + x] = 1;
                }
            }
        }
        if (core == null) return result;
        var scratch = new ShadedComponentRuns(Math.min(256, size));
        int[] paper = new int[18];
        if (hasStrictCore)
            result = find(labels, gray, width, height, gap, top, bottom, 155, core, scratch, paper);
        for (Chord candidate : find(labels, gray, width, height, gap, top, bottom, 130, core, scratch, paper)) {
            boolean overlaps = false;
            for (Chord existing : result)
                if (candidate.right >= existing.left
                        && candidate.left <= existing.right
                        && candidate.bottom >= existing.top
                        && candidate.top <= existing.bottom) {
                    overlaps = true;
                    break;
                }
            if (!overlaps) result.add(candidate);
        }
        return result;
    }

    private static List<Chord> find(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float gap,
            int top,
            int bottom,
            int minimum, byte[] core, ShadedComponentRuns scratch, int[] paper) {
        List<Chord> result = new ArrayList<>();
        int size = core.length;
        // The first pass demotes its visited pixels into the second pass.
        int required = minimum == 155 ? 2 : 1;
        byte visited = (byte) (required - 1);
        for (int seed = 0; seed < size; seed++) {
            if (core[seed] != required) continue;
            int row = seed / width;
            int queued = scratch.enqueue(core, seed, required, visited, 0);
            int area = 0, left = width, right = 0, upper = top + row, lower = upper;
            for (int read = 0; read < queued; read += 2) {
                int start = scratch.queue[read], finish = scratch.queue[read + 1];
                row = start / width;
                int rowStart = row * width;
                left = Math.min(left, start - rowStart);
                right = Math.max(right, finish - rowStart);
                lower = Math.max(lower, top + row);
                area += finish - start + 1;
                for (int step = -width; step <= width; step += 2 * width) {
                    int neighbor = start + step, limit = finish + step;
                    if (neighbor < 0 || limit >= size) continue;
                    for (int next = neighbor; next <= limit; next++) {
                        if (core[next] != required) continue;
                        queued = scratch.enqueue(core, next, required, visited, queued);
                        next = scratch.queue[queued - 1];
                    }
                }
            }
            int w = right - left + 1, h = lower - upper + 1;
            if (w < gap * .55f
                    || w > gap * 1.5f
                    || h < gap * 1.35f
                    || h > gap * 4.2f
                    || area < w * h * .5f
                    || upper == top
                    || lower == bottom) continue;
            int count = Math.round(h / gap + .2f);
            if (count < 2 || count > 4) continue;
            float headHeight = h - (count - 1) * gap;
            if (headHeight < gap * .45f || headHeight > gap * 1.15f) continue;
            int[] rowLeft = new int[h], rowRight = new int[h];
            Arrays.fill(rowLeft, width);
            for (int i = 0; i < queued; i += 2) {
                int start = scratch.queue[i], finish = scratch.queue[i + 1];
                int y = start / width, rowStart = y * width, rowIndex = top + y - upper;
                rowLeft[rowIndex] = Math.min(rowLeft[rowIndex], start - rowStart);
                rowRight[rowIndex] = Math.max(rowRight[rowIndex], finish - rowStart);
            }
            float first = (h - 1 - (count - 1) * gap) * .5f;
            int[] peaks = new int[count], peakRows = new int[count];
            boolean curved = true;
            for (int i = 0; i < count; i++) {
                int a = Math.max(0, Math.round(first + i * gap - gap * .28f)),
                        b = Math.min(h - 1, Math.round(first + i * gap + gap * .28f));
                for (int y = a; y <= b; y++) {
                    int span = rowRight[y] - rowLeft[y] + 1;
                    if (span > peaks[i]) {
                        peaks[i] = span;
                        peakRows[i] = y;
                    }
                }
                if (peaks[i] < gap * .55f) curved = false;
            }
            boolean necked = true;
            for (int i = 1; i < count; i++) {
                int neck = Integer.MAX_VALUE;
                for (int y = peakRows[i - 1] + 1; y < peakRows[i]; y++)
                    neck = Math.min(neck, rowRight[y] - rowLeft[y] + 1);
                if (neck > Math.min(peaks[i - 1], peaks[i]) - Math.max(1.5f, gap * .1f))
                    necked = false;
            }
            if (!necked
                    && !outlinedNecks(
                            gray, width, height, left, right, upper, lower, first, count, gap))
                curved = false;
            if (rowRight[0] - rowLeft[0] + 1 > peaks[0] * .9f
                    && !clippedByRule(gray, width, height, left, right, upper, gap)) curved = false;
            if (rowRight[h - 1] - rowLeft[h - 1] + 1 > peaks[count - 1] * .9f
                    && !clippedByRule(gray, width, height, left, right, lower, gap)) curved = false;
            if (!curved) continue;
            float cx = (left + right) * .5f, cy = (upper + lower) * .5f;
            int samples = 0;
            for (int side : new int[] {-1, 1})
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = 0; dx < 3; dx++) {
                        int x = Math.round(cx + side * gap * (1.3f + dx * .2f)),
                                y = Math.round(cy + dy * gap * .2f);
                        if (x >= 0 && x < width && y >= 0 && y < height)
                            paper[samples++] = gray[y * width + x] & 255;
                    }
            if (samples < 12) continue;
            Arrays.sort(paper, 0, samples);
            int paperTone = paper[samples * 3 / 4];
            if (paperTone < (minimum == 130 ? 190 : 205)) continue;
            if (minimum == 130 || paperTone < 220) {
                int[] fills = new int[area];
                int offset = top * width, index = 0;
                for (int i = 0; i < queued; i += 2)
                    for (int pixel = scratch.queue[i]; pixel <= scratch.queue[i + 1]; pixel++)
                        fills[index++] = gray[offset + pixel] & 255;
                Arrays.sort(fills);
                int fill = fills[area / 2];
                if (paperTone - fill < 50 || minimum == 130 && fill > 170) continue;
            }
            int down = stem(labels, gray, width, height, left - 3, lower - first, gap, 1);
            int up = stem(labels, gray, width, height, right + 3, upper + first, gap, -1);
            if (down < 0 && up < 0) continue;
            int l = Math.max(0, down < 0 ? left - 3 : Math.min(left - 3, down)),
                    r = Math.min(width - 1, up < 0 ? right + 3 : Math.max(right + 3, up));
            List<Head> heads = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                float y = upper + first + i * gap;
                heads.add(
                        new Head(
                                l,
                                Math.max(0, Math.round(y - gap * .45f)),
                                r,
                                Math.min(height - 1, Math.round(y + gap * .45f)),
                                cx,
                                y));
            }
            result.add(
                    new Chord(
                            l,
                            Math.max(0, upper - 3),
                            r,
                            Math.min(height - 1, lower + 3),
                            List.copyOf(heads)));
        }
        return result;
    }

    private static boolean outlinedNecks(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int top,
            int bottom,
            float first,
            int count,
            float gap) {
        for (int side : new int[] {-1, 1}) {
            int outer = Math.round((side < 0 ? left : right) + side * gap * .6f);
            int inner = Math.round((side < 0 ? left : right) - side * gap * .25f);
            if (outer < 0 || outer >= width || inner < 0 || inner >= width) continue;
            float[] edge = new float[bottom - top + 1];
            Arrays.fill(edge, Float.NaN);
            for (int y = top; y <= bottom; y++)
                for (int x = outer; side < 0 ? x <= inner : x >= inner; x -= side) {
                    if ((gray[y * width + x] & 255) < 140) {
                        if (Math.abs(x - outer) > 1) edge[y - top] = x * side;
                        break;
                    }
                }
            float[] lobes = new float[count];
            boolean complete = true;
            for (int i = 0; i < count; i++) {
                float peak = Float.NEGATIVE_INFINITY;
                int a = Math.max(0, Math.round(first + i * gap - gap * .3f)),
                        b = Math.min(edge.length - 2, Math.round(first + i * gap + gap * .3f));
                for (int y = a; y <= b; y++)
                    if (Float.isFinite(edge[y]) && Float.isFinite(edge[y + 1]))
                        peak = Math.max(peak, Math.min(edge[y], edge[y + 1]));
                lobes[i] = peak;
                if (!Float.isFinite(peak)) complete = false;
            }
            for (int i = 1; i < count; i++) {
                float neck = Float.POSITIVE_INFINITY;
                int a = Math.max(0, Math.round(first + (i - .5f) * gap - gap * .3f)),
                        b =
                                Math.min(
                                        edge.length - 2,
                                        Math.round(first + (i - .5f) * gap + gap * .3f));
                for (int y = a; y <= b; y++)
                    if (Float.isFinite(edge[y]) && Float.isFinite(edge[y + 1]))
                        neck = Math.min(neck, Math.max(edge[y], edge[y + 1]));
                if (!Float.isFinite(neck)
                        || neck > Math.min(lobes[i - 1], lobes[i]) - Math.max(1.5f, gap * .1f))
                    complete = false;
            }
            if (complete) return true;
        }
        return false;
    }

    private static boolean clippedByRule(
            byte[] gray, int width, int height, int left, int right, int edge, float gap) {
        int begin = Math.max(3, Math.round(gap * .45f)),
                reach = Math.max(begin + 2, Math.round(gap * .75f)),
                offset = Math.max(2, Math.round(gap * .2f));
        if (left - reach < 0 || right + reach >= width) return false;
        for (int y = Math.max(offset, Math.round(edge - gap * .4f));
                y <= Math.min(height - 1 - offset, Math.round(edge + gap * .4f));
                y++) {
            boolean both = true;
            for (int side : new int[] {-1, 1}) {
                int ink = 0;
                for (int d = begin; d <= reach; d++) {
                    int x = side < 0 ? left - d : right + d, v = gray[y * width + x] & 255;
                    if (v < 150
                            && (gray[(y - offset) * width + x] & 255) > v + 50
                            && (gray[(y + offset) * width + x] & 255) > v + 50) ink++;
                }
                if (ink < (reach - begin + 1) * .65f) both = false;
            }
            if (both) return true;
        }
        return false;
    }

    /** Grey fill can occlude the middle of a ledger, but both short printed rails must remain. */
    static boolean hasLedgerRails(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            float cy,
            float top,
            float bottom,
            float gap) {
        if (gray == null
                || width < 1
                || height < 1
                || gray.length != (long) width * height
                || gap < 8
                || !Float.isFinite(gap)
                || left < 0
                || right < left
                || right >= width
                || !Float.isFinite(cy)
                || !Float.isFinite(top)
                || !Float.isFinite(bottom)) return false;
        float distance = cy < top ? top - cy : cy - bottom,
                direction = cy < top ? -1 : 1,
                edge = cy < top ? top : bottom;
        int count = (int) Math.floor(distance / gap + .3f);
        if (count < 2 || count > 4) return false;
        int flank = Math.max(2, Math.round(gap * .2f));
        for (int i = 1; i <= count; i++) {
            float expected = edge + direction * gap * i;
            int[] centers = new int[2];
            for (int side = 0; side < 2; side++) {
                int a = side == 0 ? Math.max(0, left - Math.round(gap * .65f)) : right + 1;
                int b = side == 0 ? left - 1 : Math.min(width - 1, right + Math.round(gap * .65f));
                int peak = 0, row = -1;
                for (int y = Math.max(flank, Math.round(expected - gap * .3f));
                        y <= Math.min(height - 1 - flank, Math.round(expected + gap * .3f));
                        y++) {
                    int support = 0;
                    for (int x = a; x <= b; x++) {
                        int ink = gray[y * width + x] & 255;
                        if (ink < 160
                                && (gray[(y - flank) * width + x] & 255) > ink + 40
                                && (gray[(y + flank) * width + x] & 255) > ink + 40) support++;
                    }
                    if (support > peak) {
                        peak = support;
                        row = y;
                    }
                }
                if (peak < Math.max(3, Math.round(gap * .2f))) return false;
                centers[side] = row;
            }
            if (Math.abs(centers[0] - centers[1]) > gap * .2f) return false;
        }
        return true;
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
        for (int x = Math.max(0, edge - 3); x <= Math.min(width - 1, edge + 3); x++) {
            int ink = 0, semantic = 0, total = 0;
            for (int d = Math.round(gap * .75f); d <= gap * 2.3f; d++) {
                int y = Math.round(cy) + direction * d;
                if (y < 0 || y >= height) return -1;
                total++;
                if ((gray[y * width + x] & 255) < 160) ink++;
                if (labels[y * width + x] == OmrMeasurePostProcessor.STEM_OR_REST) semantic++;
            }
            if (total > 0 && ink >= total * .85f && semantic >= total * .5f) return x;
        }
        return -1;
    }
}
