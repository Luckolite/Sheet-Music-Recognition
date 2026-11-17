// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Repeated curved enclosed counters identify rounded stacked meter digits, not a double bar. */
final class RoundedMeterCounters {
    private record Pocket(float x, float y, int left, int right, boolean curved) {}

    static boolean matches(
            byte[] gray, int width, int height, int column, int top, int bottom, float gap) {
        if (gray == null || gray.length != (long) width * height || gap < 6) return false;
        int left = Math.max(0, Math.round(column - gap * 1.5f)),
                right = Math.min(width - 1, Math.round(column + gap * 1.5f));
        int first = Math.max(0, Math.round(top - gap * .2f)),
                last = Math.min(height - 1, Math.round(bottom + gap * .2f));
        int w = right - left + 1, h = last - first + 1;
        if (w < 5 || h < 10) return false;
        int stride = w + 2;
        byte[] paper = MeterCounterFlood.paperMask(gray, width, left, first, w, h);
        int[] queue = new int[w * h];
        int[] rows = new int[h], leftEdges = new int[h], rightEdges = new int[h];
        var pockets = new ArrayList<Pocket>();
        for (int seed = stride + 1, end = (h + 1) * stride; seed < end; seed++) {
            if (paper[seed] == 0) continue;
            int write = MeterCounterFlood.drain(paper, queue, stride, seed);
            if (write < gap * gap * .06f || write > gap * gap * .65f) continue;
            int minX = w, maxX = -1, minY = h, maxY = -1;
            Arrays.fill(rows, 0);
            Arrays.fill(leftEdges, w);
            Arrays.fill(rightEdges, -1);
            for (int index = 0; index < write; index++) {
                int at = queue[index], x = at % stride - 1, y = at / stride - 1;
                rows[y]++;
                leftEdges[y] = Math.min(leftEdges[y], x);
                rightEdges[y] = Math.max(rightEdges[y], x);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            int cw = maxX - minX + 1, ch = maxY - minY + 1;
            if (minX == 0
                    || maxX == w - 1
                    || minY == 0
                    || maxY == h - 1
                    || cw < gap * .2f
                    || cw > gap * 1.1f
                    || ch < gap * .25f
                    || ch > gap * .9f) continue;
            int peak = 0;
            for (int y = minY + 1; y < maxY; y++) peak = Math.max(peak, rows[y]);
            // A barline beside a wavy stroke encloses pockets with one straight edge.
            // A rounded digit needs independent curvature on both sides of its counter.
            int leftMin = w, leftMax = -1, rightMin = w, rightMax = -1;
            for (int y = minY; y <= maxY; y++) {
                leftMin = Math.min(leftMin, leftEdges[y]);
                leftMax = Math.max(leftMax, leftEdges[y]);
                rightMin = Math.min(rightMin, rightEdges[y]);
                rightMax = Math.max(rightMax, rightEdges[y]);
            }
            boolean curved =
                    peak - Math.min(rows[minY], rows[maxY]) >= Math.max(3, Math.round(gap * .18f))
                            && leftMax - leftMin >= Math.max(1, Math.round(gap * .08f))
                            && rightMax - rightMin >= Math.max(1, Math.round(gap * .08f));
            pockets.add(
                    new Pocket(
                            left + (minX + maxX) * .5f,
                            first + (minY + maxY) * .5f,
                            left + minX,
                            left + maxX,
                            curved));
        }
        if (pockets.size() < 3
                || pockets.size() > 5
                || pockets.stream().filter(Pocket::curved).count() < 3) return false;
        float minX = Float.MAX_VALUE, maxX = -1, minY = Float.MAX_VALUE, maxY = -1;
        for (var p : pockets) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        float middle = (top + bottom) * .5f;
        return maxX - minX <= gap * .5f
                && maxY - minY >= gap * 2
                && minY < middle - gap * .4f
                && maxY > middle + gap * .4f
                && Math.abs((minX + maxX) * .5f - column) <= gap * .85f;
    }
}
