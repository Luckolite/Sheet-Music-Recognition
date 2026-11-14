// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Staff-local raw candidates; the full crossed body and both dots must verify a segno. */
final class NavigationSegnoGlyphs {
    // A system heading can place the Segno slightly above the usual six-space band.
    static final float MAX_STAFF_DISTANCE = 6.25f;
    private static final float MAX_BODY_HEIGHT = 4.5f;
    private static final float MAX_BODY_WIDTH = 3.6f;

    private NavigationSegnoGlyphs() {}

    static List<ScoreNavigationDetector.Glyph> detect(
            byte[] gray, int width, int height, List<PlayingTechniqueDetector.Staff> staffs) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || staffs == null) return List.of();
        var result = new ArrayList<ScoreNavigationDetector.Glyph>();
        boolean[] seen = new boolean[0];
        int[] queue = new int[0];
        for (var staff : staffs) {
            float gap = staff.gap();
            if (gap < 5 || !Float.isFinite(gap)) continue;
            // Boundary ownership can accept a sign ending just over six gaps above the staff.
            // Leave room for a destination printed larger than the notes on its staff.
            int top =
                    Math.max(0, Math.round(staff.top()
                            - gap * (MAX_STAFF_DISTANCE + MAX_BODY_HEIGHT + .25f)));
            int bottom = Math.min(height - 1, Math.round(staff.top() - gap * .5f));
            if (bottom <= top) continue;
            int rows = bottom - top + 1, bandSize = width * rows, offset = top * width;
            boolean fresh = seen.length < bandSize;
            if (fresh) seen = new boolean[bandSize];
            if (queue.length == 0) queue = new int[Math.min(256, bandSize)];
            boolean hasIntermediateInk = false;
            for (int threshold = 80; threshold <= 140; threshold += 60) {
                if (threshold == 140 && !hasIntermediateInk) break;
                if (!fresh || threshold != 80) Arrays.fill(seen, 0, bandSize, false);
                for (int seed = 0; seed < bandSize; seed++) {
                    if (seen[seed]) continue;
                    int tone = gray[offset + seed] & 255;
                    if (tone > threshold) {
                        if (threshold == 80 && tone <= 140) hasIntermediateInk = true;
                        continue;
                    }
                    int take = 0, size = 1, l = width, r = -1, t = seed / width, b = t;
                    queue[0] = seed;
                    seen[seed] = true;
                    while (take < size) {
                        int at = queue[take++], y = at / width, row = y * width;
                        int first = at - row, last = first;
                        while (first > 0 && !seen[row + first - 1]
                                && (gray[offset + row + first - 1] & 255) <= threshold) first--;
                        while (last + 1 < width && !seen[row + last + 1]
                                && (gray[offset + row + last + 1] & 255) <= threshold) last++;
                        Arrays.fill(seen, row + first, row + last + 1, true);
                        l = Math.min(l, first);
                        r = Math.max(r, last);
                        b = Math.max(b, y);
                        // Include the diagonal neighbors at each end of the run.
                        int firstNeighbor = Math.max(0, first - 1);
                        int lastNeighbor = Math.min(width - 1, last + 1);
                        for (int step = -1; step <= 1; step += 2) {
                            int ny = y + step;
                            if (ny < 0 || ny >= rows) continue;
                            int neighborRow = row + step * width;
                            int lastPixel = neighborRow + lastNeighbor;
                            for (int next = neighborRow + firstNeighbor; next <= lastPixel; next++) {
                                if (seen[next] || (gray[offset + next] & 255) > threshold) continue;
                                seen[next] = true;
                                if (size == queue.length)
                                    queue = Arrays.copyOf(queue, size + Math.min(size, bandSize - size));
                                queue[size++] = next;
                                while (next < lastPixel && !seen[next + 1]
                                        && (gray[offset + next + 1] & 255) <= threshold) next++;
                            }
                        }
                    }
                    float w = r - l + 1, h = b - t + 1;
                    if (t == 0
                            || b == rows - 1
                            || staff.top() - (top + b) > gap * MAX_STAFF_DISTANCE + 1
                            || w < gap
                            || w > gap * MAX_BODY_WIDTH
                            || h < gap * 1.6f
                            || h > gap * MAX_BODY_HEIGHT
                            || w / h < .55f
                            || w / h > .95f) continue;
                    float glyphGap = Math.max(gap, Math.max(w / 3f, h / 3.5f));
                    if (!SegnoFragmentInk.matches(gray, width, height, l, top + t, r, top + b, glyphGap))
                        continue;
                    float cx = (l + r) * .5f / width, cy = (2 * top + t + b) * .5f / height;
                    if (result.stream()
                            .anyMatch(
                                    prior ->
                                            Math.abs(prior.centerX() - cx) * width < gap
                                                    && Math.abs(
                                                                            (prior.top()
                                                                                                    + prior
                                                                                                            .bottom())
                                                                                            * .5f
                                                                                    - cy)
                                                                    * height
                                                            < gap)) continue;
                    result.add(
                            new ScoreNavigationDetector.Glyph(
                                    ScorePlaybackDirection.Kind.SEGNO,
                                    cx,
                                    (top + t) / (float) height,
                                    (top + b) / (float) height));
                }
            }
        }
        return List.copyOf(result);
    }
}
