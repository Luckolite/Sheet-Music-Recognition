// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.BitSet;

/** Finds a physically open lobe and sharply printed ink in a bounded fingering glyph. */
final class FingeringThreeInk {
    private static final int OPENING = 90;

    private FingeringThreeInk() {}

    static boolean proves(byte[] raster, int stride, BitSet masked, int left, int top,
            int right, int bottom, JoinedDigitTemplates.Reading fit) {
        if (fit.digit() >= 0 || fit.scores()[3] < .65f || fit.scores()[3] < fit.best() - .06f)
            return false;
        int width = right - left + 1, height = bottom - top + 1;
        if (!solidInk(raster, stride, masked, left, top, width, height, .05f, .30f, .18f, .35f, 28)
                || !solidInk(raster, stride, masked, left, top, width, height, .64f, .92f, .25f, .47f, 28))
            return false;

        return opening(raster, stride, masked, left, top, width, height, .17f, .46f);
    }

    static boolean opening(byte[] raster, int stride, BitSet masked, int left, int top,
            int width, int height, float fromY, float toY) {
        int first = Math.round(height * fromY), last = Math.min(height - 1, Math.round(height * toY));
        int center = Math.round((width - 1) * .51f), span = center + 1;
        int[] queue = new int[span * (last - first + 1)];
        var visited = new BitSet(queue.length);
        int end = 0;
        for (int y = first; y <= last; y++) {
            int at = (top + y) * stride + left;
            if ((masked == null || !masked.get(at)) && (raster[at] & 255) >= OPENING) {
                int local = (y - first) * span;
                visited.set(local);
                queue[end++] = local;
            }
        }
        for (int start = 0; start < end; start++) {
            int current = queue[start];
            int x = current % span, y = current / span + first;
            if (x == center) return true;
            for (int direction = 0; direction < 4; direction++) {
                int nx = x, ny = y;
                switch (direction) {
                    case 0 -> nx--;
                    case 1 -> nx++;
                    case 2 -> ny--;
                    case 3 -> ny++;
                    default -> throw new AssertionError();
                }
                if (nx < 0 || ny < first || ny > last) continue;
                int local = (ny - first) * span + nx;
                int at = (top + ny) * stride + left + nx;
                if (!visited.get(local) && (masked == null || !masked.get(at)) && (raster[at] & 255) >= OPENING) {
                    visited.set(local);
                    queue[end++] = local;
                }
            }
        }
        return false;
    }

    static boolean solidInk(byte[] raster, int stride, BitSet masked, int left, int top,
            int width, int height, float fromX, float toX, float fromY, float toY, int inkThreshold) {
        int x1 = left + Math.round((width - 1) * fromX), x2 = left + Math.round((width - 1) * toX);
        int y1 = top + Math.round((height - 1) * fromY), y2 = top + Math.round((height - 1) * toY);
        int solid = 0;
        for (int y = y1; y <= y2; y++)
            for (int x = x1; x <= x2; x++) {
                int at = y * stride + x;
                if ((masked == null || !masked.get(at)) && (raster[at] & 255) <= inkThreshold) solid++;
            }
        return solid * 4 > (x2 - x1 + 1) * (y2 - y1 + 1);
    }
}
