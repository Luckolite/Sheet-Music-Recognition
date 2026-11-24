// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Compact complete raw components with symbolic head and independently labelled stem evidence. */
final class SymbolStemHeadRecovery {
    private static final float[] RIM_COS = new float[48];
    private static final float[] RIM_SIN = new float[48];

    static {
        for (int k = 0; k < RIM_COS.length; k++) {
            double angle = k * Math.PI / 24;
            RIM_COS[k] = (float) Math.cos(angle);
            RIM_SIN[k] = (float) Math.sin(angle);
        }
    }

    record Head(int left, int top, int right, int bottom, int area, float x, float y) {}

    static List<Head> find(
            byte[] labels, byte[] gray, int w, int h, float gap, int top, int bottom) {
        List<Head> result = new ArrayList<>();
        if (gray == null
                || labels == null
                || w < 1
                || h < 1
                || gray.length != (long) w * h
                || labels.length != gray.length
                || !Float.isFinite(gap)
                || gap < 8) return result;
        top = Math.max(0, top);
        bottom = Math.min(h - 1, bottom);
        float minWidth = gap * .65f, minHeight = gap * .55f;
        if (w - 2 < minWidth || bottom - top - 1 < minHeight) return result;
        int size = w * (bottom - top + 1), offset = top * w;
        int firstSeed = 0;
        while (firstSeed < size && (gray[offset + firstSeed] & 255) >= 205) firstSeed++;
        if (firstSeed == size) return result;
        boolean[] seen = new boolean[size];
        long[] pending = new long[Math.min(size, 64)];
        float maxWidth = gap * 1.9f, maxHeight = gap * 1.5f;
        float minArea = gap * gap * .32f;
        int firstDistance = Math.round(gap * .7f), endDistance = Math.round(gap * 2.7f);
        int[] shaftRows = null;
        for (int seed = firstSeed; seed < size; seed++) {
            if (seen[seed] || (gray[offset + seed] & 255) >= 205) continue;
            int rowStart = seed - seed % w;
            pending[0] = takeRun(gray, seen, offset, seed, rowStart, rowStart + w - 1);
            // Every pixel in the seed run belongs to this component.
            seed = (int) pending[0];
            int depth = 1, count = 0;
            int l = w, r = 0, t = h, b = 0, sy = 0, nh = 0;
            long sx = 0, yy = 0;
            boolean eligible = true;
            while (depth > 0) {
                long run = pending[--depth];
                int start = (int) (run >>> 32), end = (int) run;
                int row = start / w, base = row * w, y = top + row;
                int left = start - base, right = end - base, length = right - left + 1;
                if (eligible) {
                    l = Math.min(l, left);
                    r = Math.max(r, right);
                    t = Math.min(t, y);
                    b = Math.max(b, y);
                    eligible = l > 0 && r < w - 1 && t > top && b < bottom
                            && r - l + 1 <= maxWidth && b - t + 1 <= maxHeight;
                    if (eligible) {
                        count += length;
                        sx += ((long) left + right) * length / 2;
                        yy += (long) y * length;
                        for (int at = offset + start, last = offset + end; at <= last; at++) {
                            byte label = labels[at];
                            if (label == 5) sy++;
                            if (label == 2) nh++;
                        }
                    }
                }
                // Include one column beyond each end to preserve diagonal connectivity.
                int firstX = Math.max(0, left - 1), lastX = Math.min(w - 1, right + 1);
                for (int direction = -1; direction <= 1; direction += 2) {
                    int neighbor = base + direction * w;
                    if (neighbor < 0 || neighbor >= size) continue;
                    for (int next = neighbor + firstX; next <= neighbor + lastX; next++) {
                        if (seen[next] || (gray[offset + next] & 255) >= 205) continue;
                        long adjacent = takeRun(gray, seen, offset, next, neighbor, neighbor + w - 1);
                        if (depth == pending.length)
                            pending = Arrays.copyOf(pending, depth + Math.min(depth, size - depth));
                        pending[depth++] = adjacent;
                        next = (int) adjacent;
                    }
                }
            }
            if (!eligible || r - l + 1 < minWidth || b - t + 1 < minHeight
                    || count < minArea
                    || sy < count * .4f
                    || nh > count * .2f) continue;
            float cy = yy / (float) count;
            boolean stem = false;
            shafts: for (int direction = -1; direction <= 1; direction += 2) {
                int first = firstDistance, end = endDistance;
                while (first < end) {
                    int y = Math.round(cy + direction * first);
                    if (y >= 0 && y < h) break;
                    first++;
                }
                while (end > first) {
                    int y = Math.round(cy + direction * (end - 1));
                    if (y >= 0 && y < h) break;
                    end--;
                }
                int total = end - first;
                if (total < gap) continue;
                if (shaftRows == null || shaftRows.length < total) shaftRows = new int[total];
                for (int i = 0; i < total; i++)
                    shaftRows[i] = Math.round(cy + direction * (first + i)) * w;
                float requiredInk = total * .85f, requiredLabels = total * .5f;
                int edge = direction == 1 ? l : r;
                for (int x = Math.max(0, edge - 3); x <= Math.min(w - 1, edge + 3); x++) {
                    int ink = 0, semantic = 0;
                    for (int i = 0; i < total; i++) {
                        int at = shaftRows[i] + x;
                        if ((gray[at] & 255) < 205) ink++;
                        if (labels[at] == 1) semantic++;
                    }
                    if (ink >= requiredInk && semantic >= requiredLabels) {
                        stem = true;
                        break shafts;
                    }
                }
            }
            if (stem) result.add(new Head(l, t, r, b, count, sx / (float) count, cy));
        }
        return result;
    }

    private static long takeRun(
            byte[] gray, boolean[] seen, int offset, int seed, int rowStart, int rowEnd) {
        int left = seed, right = seed;
        // Discovery marks whole runs, so an unseen seed belongs to an entirely unseen run.
        while (left > rowStart && (gray[offset + left - 1] & 255) < 205)
            left--;
        while (right < rowEnd && (gray[offset + right + 1] & 255) < 205)
            right++;
        Arrays.fill(seen, left, right + 1, true);
        return ((long) left << 32) | right;
    }

    /**
     * Broken oval rims still need opposing edge contrast and an owned labelled shaft. The caller
     * must independently prove a complete beam to a retained note before admission.
     */
    static List<Head> sparseOvals(
            byte[] labels, byte[] gray, int w, int h, float gap, int top, int bottom) {
        List<Head> out = new ArrayList<>();
        if (gray == null
                || labels == null
                || w < 3
                || h < 3
                || gray.length != (long) w * h
                || labels.length != gray.length
                || !Float.isFinite(gap)
                || gap < 8) return out;
        List<Integer> scores = new ArrayList<>();
        float rx = gap * .69f, ry = gap * .4f;
        int margin = (int) Math.ceil(gap * 1.4f),
                stride = Math.max(1, Math.round(gap * .14f)),
                pixelRadius = Math.max(1, Math.round(gap * .07f));
        int firstDistance = Math.round(gap * .8f), lastDistance = Math.round(gap * 2.5f);
        int shaftRadius = Math.max(3, Math.round(gap * .22f));
        int halfHeight = (int) Math.ceil(Math.sqrt(ry * ry + .16f * rx * rx));
        long firstRow = Math.max(0, top) + (long) margin;
        long lastRow = Math.min(bottom, h - 1) - (long) margin;
        if (firstRow > lastRow || margin >= w - margin) return out;
        int firstY = (int) firstRow, lastY = (int) lastRow;
        int[] ovalBounds = null;
        float minArea = gap * gap * .3f, duplicateWidth = gap * .8f, duplicateHeight = gap * .65f;
        int[] rim = new int[48], outer = new int[48];
        // Add integer raster offsets unless floating-point addition could cross a rounding
        // boundary. In that case preserve the original rounding at each candidate center.
        long ambiguous = 0;
        float precision = Math.ulp((float) Math.max(w, h));
        for (int k = 0; k < rim.length; k++) {
            float dx = rx * RIM_COS[k], dy = ry * RIM_SIN[k] - .4f * dx;
            float ox = dx * 1.6f, oy = dy * 1.6f;
            rim[k] = Math.round(dy) * w + Math.round(dx);
            outer[k] = Math.round(oy) * w + Math.round(ox);
            if (precision >= .5f || nearHalf(dx, precision) || nearHalf(dy, precision)
                    || nearHalf(ox, precision) || nearHalf(oy, precision))
                ambiguous |= 1L << k;
        }
        var upper = new ShaftSpan();
        var lower = new ShaftSpan();
        int limit = w - margin;
        for (int y = firstY; y <= lastY; y += stride) {
            boolean hasUpper = upper.reset(w, h, y, gap, firstDistance, lastDistance, -1);
            boolean hasLower = lower.reset(w, h, y, gap, firstDistance, lastDistance, 1);
            if (!hasUpper && !hasLower) continue;
            candidates: for (int x = margin; x < limit; x += stride) {
                if (!(hasUpper && upper.owns(labels, gray, w, x + rx, shaftRadius))
                        && !(hasLower && lower.owns(labels, gray, w, x - rx, shaftRadius))) {
                    int next = hasUpper ? upper.nextCenter(x, stride, rx, shaftRadius, limit) : limit;
                    if (hasLower)
                        next = Math.min(next, lower.nextCenter(x, stride, -rx, shaftRadius, limit));
                    x = next - stride;
                    continue;
                }
                int center = y * w + x, quadrantMisses = 0, misses = 0;
                for (int k = 0; k < 48; k++) {
                    int outerIndex = center + outer[k], rimIndex = center + rim[k];
                    if ((ambiguous & (1L << k)) != 0) {
                        float dx = rx * RIM_COS[k], dy = ry * RIM_SIN[k] - .4f * dx;
                        outerIndex = Math.round(y + dy * 1.6f) * w + Math.round(x + dx * 1.6f);
                        rimIndex = Math.round(y + dy) * w + Math.round(x + dx);
                    }
                    int maxInk = Math.min(204, (gray[outerIndex] & 255) - 30);
                    if (maxInk < 0 || !rimInk(gray, w, rimIndex, pixelRadius, maxInk)) {
                        if (++misses > 9 || ++quadrantMisses > 4) continue candidates;
                    }
                    if ((k + 1) % 12 == 0) quadrantMisses = 0;
                }
                int score = 48 - misses;
                int duplicate = -1;
                for (int i = 0; i < out.size(); i++) {
                    Head prior = out.get(i);
                    if (Math.abs(prior.y - y) < duplicateHeight
                            && Math.abs(prior.x - x) < duplicateWidth) {
                        duplicate = i;
                        break;
                    }
                }
                if (duplicate >= 0 && score <= scores.get(duplicate)) continue;
                if (ovalBounds == null) ovalBounds = ovalRowBounds(rx, ry, halfHeight);
                int area = 0, glyphInk = 0;
                boolean hasNotehead = false;
                for (int row = 0, base = (y - halfHeight) * w + x;
                        row < ovalBounds.length; row += 2, base += w) {
                    for (int at = base + ovalBounds[row], last = base + ovalBounds[row + 1];
                            at <= last; at++) {
                        if ((gray[at] & 255) >= 225) continue;
                        area++;
                        if (labels[at] == OmrMeasurePostProcessor.CLEF_OR_KEY) glyphInk++;
                        if (labels[at] == OmrMeasurePostProcessor.NOTEHEAD) hasNotehead = true;
                    }
                }
                if (area < minArea || !hasNotehead && glyphInk >= .55f * area
                        && sharpOwnsOval(labels, gray, w, h, x, y, gap, Math.round(rx))) continue;
                Head candidate = new Head(
                        Math.round(x - rx), y - halfHeight, Math.round(x + rx),
                        y + halfHeight, area, x, y);
                if (duplicate < 0) {
                    out.add(candidate);
                    scores.add(score);
                } else {
                    out.set(duplicate, candidate);
                    scores.set(duplicate, score);
                }
            }
        }
        return out;
    }

    /** A sharp's two uprights can supply a false rim beside a real borrowed shaft. */
    private static boolean sharpOwnsOval(byte[] labels, byte[] gray, int width, int height,
            int x, int y, float gap, int radius) {
        int length = Math.round(gap * 2.1f);
        int minimumSeparation = Math.round(gap * .37f);
        int maximumSeparation = Math.round(gap * .70f);
        for (int direction : new int[] {-1, 1}) {
            if (y + direction * length < 0 || y + direction * length >= height) continue;
            int witness = Integer.MIN_VALUE / 2;
            for (int column = x - radius; column <= x + radius; column++) {
                int owned = 0;
                for (int i = 0; i <= length; i++) {
                    int at = (y + direction * i) * width + column;
                    if (labels[at] == OmrMeasurePostProcessor.CLEF_OR_KEY && (gray[at] & 255) < 165)
                        owned++;
                }
                if (owned < (length + 1) * .92f) continue;
                int separation = column - witness;
                if (separation >= minimumSeparation && separation <= maximumSeparation) return true;
                if (separation > maximumSeparation) witness = column;
            }
        }
        return false;
    }

    private static boolean nearHalf(float delta, float precision) {
        double distance = Math.abs((double) delta - Math.round(delta));
        // Exact half-integers are representable throughout the fast path's coordinate range.
        return distance != .5 && distance >= .5 - precision;
    }

    private static boolean rimInk(byte[] gray, int width, int center, int radius, int maxInk) {
        int first = center - radius * width - radius;
        if (radius == 1) {
            return threePixels(gray, first, maxInk) || threePixels(gray, first + width, maxInk)
                    || threePixels(gray, first + width * 2, maxInk);
        }
        for (int row = 0, base = first; row <= 2 * radius; row++, base += width)
            for (int at = base, end = base + 2 * radius; at <= end; at++)
                if ((gray[at] & 255) <= maxInk) return true;
        return false;
    }

    private static boolean threePixels(byte[] gray, int at, int maxInk) {
        return (gray[at] & 255) <= maxInk || (gray[at + 1] & 255) <= maxInk
                || (gray[at + 2] & 255) <= maxInk;
    }

    private static int[] ovalRowBounds(float rx, float ry, int halfHeight) {
        int[] bounds = new int[(2 * halfHeight + 1) * 2];
        float rxSquared = rx * rx, rySquared = ry * ry;
        for (int dy = -halfHeight, row = 0; dy <= halfHeight; dy++, row += 2) {
            int left = (int) rx + 1, right = -left;
            for (int dx = -(int) rx; dx <= rx; dx++) {
                float sheared = dy + .4f * dx;
                if (dx * dx / rxSquared + sheared * sheared / rySquared <= 1) {
                    left = Math.min(left, dx);
                    right = dx;
                }
            }
            bounds[row] = left;
            bounds[row + 1] = right;
        }
        return bounds;
    }

    private static final class ShaftSpan {
        private int middleRow, firstRow, step, total;
        private int requiredRaw, requiredSemantic;
        private int witness = -1;

        boolean reset(
                int width, int height, int y, float gap, int firstDistance, int lastDistance,
                int direction) {
            int middle = Math.round(y + direction * gap * 1.5f);
            if (middle < 0 || middle >= height) return false;
            int end = Math.min(lastDistance, direction < 0 ? y : height - 1 - y);
            total = end - firstDistance + 1;
            if (total < gap) return false;
            middleRow = middle * width;
            firstRow = (y + direction * firstDistance) * width;
            step = direction * width;
            requiredRaw = (int) Math.ceil(total * .85f);
            requiredSemantic = (int) Math.ceil(total * .5f);
            witness = -1;
            return true;
        }

        boolean owns(byte[] labels, byte[] gray, int width, float center, int radius) {
            int shaft = Math.round(center);
            int left = Math.max(0, shaft - radius), right = Math.min(width - 1, shaft + radius);
            if (witness < left) {
                witness = Integer.MAX_VALUE;
                for (int column = left; column < width; column++) {
                    int middle = middleRow + column;
                    if (labels[middle] == 1 && (gray[middle] & 255) < 205
                            && supportsShaft(labels, gray, firstRow + column)) {
                        witness = column;
                        break;
                    }
                }
            }
            return witness <= right;
        }

        // The next eligible column was already found. Locate the first remaining grid
        // point whose rounded shaft window can reach it.
        int nextCenter(int x, int stride, float offset, int radius, int limit) {
            if (witness == Integer.MAX_VALUE) return limit;
            int lo = 1, hi = (limit - 1 - x) / stride + 1;
            while (lo < hi) {
                int mid = lo + (hi - lo) / 2;
                if (Math.round(x + mid * stride + offset) + radius < witness) lo = mid + 1;
                else hi = mid;
            }
            return Math.min(limit, x + lo * stride);
        }

        private boolean supportsShaft(byte[] labels, byte[] gray, int at) {
            int raw = 0, semantic = 0;
            int remaining = total;
            while (remaining >= 8) {
                for (int samples = 0; samples < 8; samples++, at += step) {
                    if ((gray[at] & 255) < 205) raw++;
                    if (labels[at] == 1) semantic++;
                }
                remaining -= 8;
                if (raw + remaining < requiredRaw || semantic + remaining < requiredSemantic)
                    return false;
            }
            for (; remaining > 0; remaining--, at += step) {
                if ((gray[at] & 255) < 205) raw++;
                if (labels[at] == 1) semantic++;
            }
            return raw >= requiredRaw && semantic >= requiredSemantic;
        }
    }
}
