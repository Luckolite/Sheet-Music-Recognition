// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** Raw compact rising grace hook. Call only after a stemmed grace on its left and
 * independently stemmed principal note on its right have established ownership. */
final class GraceConnectorInk {
    private GraceConnectorInk() {}

    static boolean isHook(
            byte[] gray,
            int width,
            int height,
            int minX,
            int minY,
            int maxX,
            int maxY,
            float centerX,
            float centerY,
            int area,
            float gap,
            int inkThreshold) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || gap < 5
                || !Float.isFinite(gap)
                || area <= 0
                || minX < 0
                || minY < 0
                || maxX < minX
                || maxY < minY
                || maxX >= width
                || maxY >= height) return false;
        int left = Math.max(0, Math.round(centerX - gap * 2)),
                right = Math.min(width - 1, Math.round(centerX + gap * 2));
        int top = Math.max(0, Math.round(centerY - gap * 1.2f)),
                bottom = Math.min(height - 1, Math.round(centerY + gap * 1.2f));
        int w = right - left + 1, h = bottom - top + 1;
        float minSpan = Math.max(gap * .75f, (maxX - minX + 1) * .95f);
        float minRise = gap * .4f;
        if (w - 2 < minSpan || h - 2 < minRise) return false;
        float minOverlap = area * .45f;
        // An accepted component lies strictly inside the crop.
        int overlapLeft = Math.max(left + 1, minX) - left;
        int overlapRight = Math.min(right - 1, maxX) - left;
        int overlapWidth = overlapRight - overlapLeft + 1;
        int overlapFirstRow = Math.max(top + 1, minY) - top;
        int overlapLastRow = Math.min(bottom - 1, maxY) - top;
        int overlapHeight = overlapLastRow - overlapFirstRow + 1;
        if (overlapWidth <= 0 || overlapHeight <= 0
                || (long) overlapWidth * overlapHeight < minOverlap) return false;

        int firstY = 0, firstX = 0;
        for (; firstY < h; firstY++) {
            int row = (top + firstY) * width + left;
            firstX = 0;
            while (firstX < w && (gray[row + firstX] & 255) > inkThreshold) firstX++;
            if (firstX < w) break;
        }
        if (firstY > overlapLastRow) return false;
        int availableOverlapRows = overlapLastRow - Math.max(firstY, overlapFirstRow) + 1;
        if ((long) overlapWidth * availableOverlapRows < minOverlap) return false;

        boolean[] rules = new boolean[h];
        float ruleInk = w * .9f;
        // At large widths an int-to-float comparison can round the ink count.
        int minimumRuleInk = (int) Math.ceil(ruleInk);
        while (minimumRuleInk > 0 && minimumRuleInk - 1 >= ruleInk) minimumRuleInk--;
        for (int y = firstY; y < h; y++) {
            int row = (top + y) * width;
            // Unread pixels can still be ink. Stop once even that cannot qualify the row.
            int knownPaper = y == firstY ? firstX : 0;
            int knownInk = y == firstY ? 1 : 0;
            int possible = w - knownPaper;
            for (int at = row + left + knownPaper + knownInk, end = row + right;
                    at <= end && possible >= minimumRuleInk; at++)
                if ((gray[at] & 255) > inkThreshold) possible--;
            rules[y] = possible >= minimumRuleInk;
        }
        int maxRuleThickness = Math.max(2, Math.round(gap * .3f));
        for (int y = firstY; y < h; ) {
            int start = y;
            while (y < h && rules[y]) y++;
            if (y == start) {
                y++;
            } else if (y - start > maxRuleThickness) {
                Arrays.fill(rules, start, y, false);
            } else {
                availableOverlapRows -= Math.max(0,
                        Math.min(y, overlapLastRow + 1) - Math.max(start, overlapFirstRow));
            }
        }
        if ((long) overlapWidth * availableOverlapRows < minOverlap) return false;
        int cells = w * h;
        // Padding the width to a power of two less than doubles the crop's cell
        // count, so the coordinates fit in an unsigned int.
        int columnBits = Integer.SIZE - Integer.numberOfLeadingZeros(w - 1);
        int columnMask = (1 << columnBits) - 1;
        boolean[] seen = null;
        int[] stack = null;
        int[] columns = null;
        float[] means = null;
        int[] bins = null;
        float medianLimit = gap * .45f, upperLimit = gap * .55f;
        float minimumRise = gap * .18f, minimumCurve = Math.max(.35f, gap * .025f);
        float maxSpan = gap * 1.8f, maxRise = gap * 1.2f;
        int previousLeft = 0, previousRight = 0;
        // A component seeded below the candidate cannot intersect it.
        for (int sy = firstY; sy <= overlapLastRow; sy++) {
            if (rules[sy]) continue;
            int row = (top + sy) * width + left;
            int startX = sy == firstY ? firstX : 0;
            for (int sx = startX, seed = sy * w + startX; sx < w; sx++, seed++) {
                if ((seen != null && seen[seed]) || (gray[row + sx] & 255) > inkThreshold) continue;
                if (seen == null) {
                    seen = new boolean[cells];
                    stack = new int[Math.min(128, cells)];
                }
                int end = 1;
                stack[0] = (sy << columnBits) | sx;
                seen[seed] = true;
                // The first unvisited seed is already on the highest row of its component.
                int l = w, r = -1, b = sy, overlap = 0;
                if (columns == null) {
                    columns = new int[w * 2];
                } else {
                    Arrays.fill(columns, previousLeft * 2, previousRight * 2, 0);
                }
                while (end > 0) {
                    int at = stack[--end];
                    int y = at >>> columnBits;
                    int x = at & columnMask;
                    l = Math.min(l, x);
                    r = Math.max(r, x);
                    b = Math.max(b, y);
                    int column = x * 2;
                    columns[column]++;
                    columns[column + 1] += y;
                    if (x >= overlapLeft && x <= overlapRight
                            && y >= overlapFirstRow && y <= overlapLastRow)
                        overlap++;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        while (dy != 0 && yy >= 0 && yy < h && rules[yy]) yy += dy;
                        if (yy < 0 || yy >= h) continue;
                        // Horizontal neighbors move one column. Diagonals move one per crossed row.
                        int reach = dy == 0 ? 1 : Math.abs(yy - y);
                        int target = yy * w, raster = (top + yy) * width + left;
                        int packedRow = yy << columnBits;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx * reach;
                            if (xx < 0 || xx >= w) continue;
                            int next = target + xx;
                            if (!seen[next] && (gray[raster + xx] & 255) <= inkThreshold) {
                                seen[next] = true;
                                if (end == stack.length)
                                    stack = Arrays.copyOf(stack, end + Math.min(end, cells - end));
                                stack[end++] = packedRow | xx;
                            }
                        }
                    }
                }
                previousLeft = l;
                previousRight = r + 1;
                int span = r - l + 1, rise = b - sy + 1;
                if (l == 0
                        || r == w - 1
                        || sy == 0
                        || b == h - 1
                        || overlap < minOverlap
                        || span < minSpan
                        || span > maxSpan
                        || rise < minRise
                        || rise > maxRise) continue;
                if (means == null) {
                    means = new float[3];
                    bins = new int[3];
                } else {
                    Arrays.fill(means, 0f);
                    Arrays.fill(bins, 0);
                }
                int used = 0, withinMedianLimit = 0, withinUpperLimit = 0;
                int firstColumn = l * 2;
                for (int column = firstColumn, limit = r * 2; column <= limit; column += 2) {
                    int count = columns[column];
                    if (count > 0) {
                        int bin = Math.min(2, ((column - firstColumn) / 2) * 3 / span);
                        means[bin] += columns[column + 1] / (float) count;
                        bins[bin]++;
                        used++;
                        if (count <= medianLimit) withinMedianLimit++;
                        if (count <= upperLimit) withinUpperLimit++;
                    }
                }
                // A sorted rank passes exactly when more than that many columns meet its limit.
                if (withinMedianLimit <= used / 2 || withinUpperLimit <= used * 3 / 4
                        || bins[0] == 0 || bins[1] == 0 || bins[2] == 0) continue;
                for (int i = 0; i < 3; i++) means[i] /= bins[i];
                // A thin hook rises toward its principal note and curves more sharply
                // at that end. Filled/hollow ovals and straight strokes do not have
                // this asymmetric centreline even when their mask is fragmented.
                if (means[0] - means[2] < minimumRise
                        || means[0] <= means[1]
                        || means[1] <= means[2]
                        || means[1] - (means[0] + means[2]) * .5f < minimumCurve)
                    continue;
                return true;
            }
        }
        return false;
    }
}
