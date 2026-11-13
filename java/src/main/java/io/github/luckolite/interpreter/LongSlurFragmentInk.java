// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** Complete long return curves, used only for tiny stemless fragments beside a real note. */
final class LongSlurFragmentInk {
    private LongSlurFragmentInk() {}

    static boolean matches(
            byte[] gray,
            int width,
            int height,
            int minX,
            int minY,
            int maxX,
            int maxY,
            int area,
            float gap) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || gap < 5
                || !Float.isFinite(gap)
                || minX < 0
                || minY < 0
                || maxX >= width
                || maxY >= height
                || area <= 0) return false;
        float cx = (minX + maxX) * .5f, cy = (minY + maxY) * .5f;
        int left = Math.max(0, Math.round(cx - gap * 12)),
                right = Math.min(width - 1, Math.round(cx + gap * 12));
        int top = Math.max(0, Math.round(cy - gap * 4)),
                bottom = Math.min(height - 1, Math.round(cy + gap * 4));
        int w = right - left + 1, h = bottom - top + 1;
        float minimumSpan = gap * 3, minimumRise = gap * .5f;
        float maximumSpan = gap * 20, maximumRise = gap * 3.5f;
        int minimumOverlap = minimumPixelCount(area * .4f);
        if (w - 2 < minimumSpan || h - 2 < minimumRise) return false;
        int seedLeft = Math.max(left + 1, minX) - left;
        int seedRight = Math.min(right - 1, maxX) - left;
        int seedTop = Math.max(top + 1, minY) - top;
        int seedBottom = Math.min(bottom - 1, maxY) - top;
        int overlapWidth = seedRight - seedLeft + 1;
        int overlapHeight = seedBottom - seedTop + 1;
        if (overlapWidth <= 0 || overlapHeight <= 0
                || (long) overlapWidth * overlapHeight < minimumOverlap) return false;
        int firstThreshold = firstPossibleThreshold(
                gray, width, left + seedLeft, top + seedTop, left + seedRight, top + seedBottom,
                minimumOverlap);
        if (firstThreshold > 140) return false;
        int minimumRuleInk = minimumPixelCount(w * .9f);
        int maximumRuleThickness = Math.max(2, Math.round(gap * .3f));
        float medianThickness = gap * .65f, upperThickness = gap * .8f;
        float maximumThickness = gap * 1.3f;
        float minimumBend = gap * .45f, minimumShoulder = gap * .10f;
        int cells = w * h;
        // Padding the crop width to a power of two less than doubles its area.
        int columnBits = Integer.SIZE - Integer.numberOfLeadingZeros(w - 1);
        int columnMask = (1 << columnBits) - 1;
        float localCenterX = cx - left;
        int[] queue = null;
        int[] columns = null;
        float[] means = null;
        int[] bins = null;
        boolean[] rules = new boolean[h];
        for (int threshold = firstThreshold; threshold <= 140; threshold += 30) {
            int availableOverlapRows = overlapHeight;
            int ruleStart = -1;
            for (int y = 0; y < h; y++) {
                int row = (top + y) * width + left;
                int possible = w;
                for (int at = row, end = row + w; at < end && possible >= minimumRuleInk; at++)
                    if ((gray[at] & 255) > threshold) possible--;
                rules[y] = possible >= minimumRuleInk;
                if (rules[y] && ruleStart < 0) ruleStart = y;
                if (ruleStart >= 0 && (!rules[y] || y == h - 1)) {
                    int ruleEnd = rules[y] ? h : y;
                    if (ruleEnd - ruleStart > maximumRuleThickness) {
                        Arrays.fill(rules, ruleStart, ruleEnd, false);
                    } else {
                        availableOverlapRows -= Math.max(0,
                                Math.min(ruleEnd, seedBottom + 1) - Math.max(ruleStart, seedTop));
                    }
                    ruleStart = -1;
                }
            }
            if ((long) overlapWidth * availableOverlapRows < minimumOverlap) continue;
            boolean[] visited = new boolean[cells];
            for (int sy = seedTop; sy <= seedBottom; sy++) {
                if (rules[sy]) continue;
                int rasterRow = (top + sy) * width + left;
                int firstSeed = sy * w + seedLeft;
                for (int sx = seedLeft, seed = firstSeed; sx <= seedRight; sx++, seed++) {
                    if (visited[seed] || (gray[rasterRow + sx] & 255) > threshold)
                        continue;
                    if (queue == null) {
                        queue = new int[Math.min(128, cells)];
                    }
                    int end = 1, take = 0;
                    queue[0] = (sy << columnBits) | sx;
                    visited[seed] = true;
                    int l = w, r = -1, t = h, b = -1, overlap = 0;
                    while (take < end) {
                        int point = queue[take++];
                        int y = point >>> columnBits, x = point & columnMask;
                        l = Math.min(l, x);
                        r = Math.max(r, x);
                        t = Math.min(t, y);
                        b = Math.max(b, y);
                        if (overlap < minimumOverlap
                                && x >= seedLeft && x <= seedRight && y >= seedTop && y <= seedBottom)
                            overlap++;
                        for (int dy = -1; dy <= 1; dy++) {
                            int yy = y + dy;
                            if (yy < 0 || yy >= h || rules[yy]) continue;
                            int row = yy * w, raster = (top + yy) * width + left;
                            int packedRow = yy << columnBits;
                            for (int dx = -1; dx <= 1; dx++) {
                                int xx = x + dx;
                                if (xx < 0 || xx >= w) continue;
                                int next = row + xx;
                                if (!visited[next] && (gray[raster + xx] & 255) <= threshold) {
                                    visited[next] = true;
                                    if (end == queue.length)
                                        queue = Arrays.copyOf(queue, end + Math.min(end, cells - end));
                                    queue[end++] = packedRow | xx;
                                }
                            }
                        }
                    }
                    int span = r - l + 1, rise = b - t + 1;
                    if (l == 0
                            || r == w - 1
                            || t == 0
                            || b == h - 1
                            || overlap < minimumOverlap
                            || span < minimumSpan
                            || span > maximumSpan
                            || span < rise * 2
                            || rise < minimumRise
                            || rise > maximumRise) continue;
                    float relative = (localCenterX - l) / span;
                    if (relative > .22f && relative < .78f) continue;
                    int profileSize = span * 2;
                    if (columns == null || columns.length < profileSize) {
                        columns = new int[profileSize];
                    } else {
                        Arrays.fill(columns, 0, profileSize, 0);
                    }
                    // The queue retains all pixels, so rejected components need no column profile.
                    for (int i = 0; i < end; i++) {
                        int point = queue[i], column = ((point & columnMask) - l) * 2;
                        columns[column]++;
                        columns[column + 1] += point >>> columnBits;
                    }
                    if (means == null) {
                        means = new float[5];
                        bins = new int[5];
                    } else {
                        Arrays.fill(means, 0f);
                        Arrays.fill(bins, 0);
                    }
                    int used = 0, withinMedian = 0, withinUpper = 0, thickest = 0;
                    for (int x = 0; x < span; x++) {
                        int column = x * 2, count = columns[column];
                        if (count > 0) {
                            int bin = Math.min(4, x * 5 / span);
                            means[bin] += columns[column + 1] / (float) count;
                            bins[bin]++;
                            used++;
                            if (count <= medianThickness) withinMedian++;
                            if (count <= upperThickness) withinUpper++;
                            thickest = Math.max(thickest, count);
                        }
                    }
                    if (withinMedian <= used / 2 || withinUpper <= used * 3 / 4
                            || thickest > maximumThickness) continue;
                    if (bins[0] == 0 || bins[1] == 0 || bins[2] == 0 || bins[3] == 0 || bins[4] == 0)
                        continue;
                    for (int i = 0; i < 5; i++) means[i] /= bins[i];
                    if (Math.abs(means[0] - means[4]) > upperThickness) continue;
                    float bend = means[2] - (means[0] + means[4]) * .5f;
                    if (Math.abs(bend) < minimumBend) continue;
                    int direction = bend > 0 ? 1 : -1;
                    if ((means[1] - means[0]) * direction < minimumShoulder
                            || (means[2] - means[1]) * direction < 0
                            || (means[3] - means[4]) * direction < minimumShoulder
                            || (means[2] - means[3]) * direction < 0) continue;
                    return true;
                }
            }
        }
        return false;
    }

    private static int minimumPixelCount(float quota) {
        int count = (int) Math.ceil(quota);
        // Preserve the original int-to-float comparison for large pixel counts.
        while (count > 0 && count - 1 >= quota) count--;
        return count;
    }

    private static int firstPossibleThreshold(
            byte[] gray, int width, int left, int top, int right, int bottom, int overlap) {
        int dark = 0, middle = 0, pale = 0;
        for (int y = top; y <= bottom; y++) {
            int row = y * width;
            for (int at = row + left, end = row + right; at <= end; at++) {
                int tone = gray[at] & 255;
                if (tone <= 80) {
                    if (++dark >= overlap) return 80;
                } else if (tone <= 110) {
                    middle++;
                } else if (tone <= 140) {
                    pale++;
                }
            }
        }
        if (dark + middle >= overlap) return 110;
        return dark + middle + pale >= overlap ? 140 : 255;
    }
}
