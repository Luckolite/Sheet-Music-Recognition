// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Local-background evidence for existing sharp-shape verification, never a classifier alone. */
final class FaintSharpInk {
    private static final int MAX_REUSED_ROWS = 128;
    private static final ThreadLocal<long[]> ROW_PAIRS =
            ThreadLocal.withInitial(() -> new long[MAX_REUSED_ROWS]);

    static byte[] crop(
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            float gap) {
        return crop(gray, width, height, left, top, right, bottom, gap, .2f);
    }

    /** Only for independently paired semantic crossbars followed by full sharp-shape proof. */
    static byte[] cropPairedCrossbars(
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            float gap) {
        return crop(gray, width, height, left, top, right, bottom, gap, .35f);
    }

    private static byte[] crop(
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            float gap,
            float maximumGap) {
        if (gray == null
                || gray.length != (long) width * height
                || !Float.isFinite(gap)
                || gap < 4
                || left < 0
                || top < 0
                || right >= width
                || bottom >= height
                || right < left
                || bottom < top) return null;
        int side = Math.max(2, Math.round(gap * .25f)), probe = Math.max(2, Math.round(gap * .2f));
        if (left < side || right + side >= width || top < probe + 1 || bottom + probe + 1 >= height)
            return null;
        int w = right - left + 1, h = bottom - top + 1, reach = Math.max(3, Math.round(gap * .65f));
        int firstFlank = Math.max(0, left - reach), lastFlank = Math.min(width - 1, right + reach);
        int outside = left - firstFlank + lastFlank - right;
        int allowedBright = outside - (int) Math.ceil(outside * .8);
        int probeStride = probe * width;
        byte[] mask = new byte[w * h];
        long[] pairs = null;
        if (w <= Long.SIZE && h >= 5)
            pairs = h <= MAX_REUSED_ROWS ? ROW_PAIRS.get() : new long[h];
        long previous = 0;
        for (int y = top, row = top * width, dest = 0;
                y <= bottom; y++, row += width, dest += w) {
            int firstInk = row + left, end = row + right;
            while (firstInk <= end && !ink(gray, width, firstInk, side)) firstInk++;
            if (firstInk > end) {
                if (pairs != null) pairs[y - top] = 0;
                previous = 0;
                continue;
            }
            boolean rule = staffRule(gray, row + firstFlank, row + left,
                    row + right + 1, row + lastFlank, allowedBright);
            int at = firstInk;
            long current = 0;
            do {
                if (!rule
                        || ink(gray, width, at - probeStride, side)
                                && ink(gray, width, at + probeStride, side)) {
                    int x = at - row - left;
                    mask[dest + x] = OmrMeasurePostProcessor.CLEF_OR_KEY;
                    if (pairs != null) current |= 1L << x;
                }
                do {
                    at++;
                } while (at <= end && !ink(gray, width, at, side));
            } while (at <= end);
            if (pairs != null) {
                pairs[y - top] = previous & current;
                previous = current;
            }
        }
        if (h < 5) return mask;
        if (pairs != null) return closeNarrowGaps(mask, pairs, w, h, gap, maximumGap);
        byte[] closed = mask;
        int limit = Math.min(h - 4, Math.max(1, Math.round(gap * maximumGap)));
        for (int y = 1, row = w; y < h - 3; y++, row += w) {
            int lastSkip = Math.min(limit, h - y - 3);
            for (int x = 0; x < w; x++) {
                if (mask[row + x] == 0 || mask[row - w + x] == 0) continue;
                for (int skip = 1, downstream = row + 2 * w;
                        skip <= lastSkip; skip++, downstream += w) {
                    int radius = Math.max(1, Math.round((skip + 1) * .3f));
                    int endLimit = Math.min(w - 1, x + radius);
                    for (int end = Math.max(0, x - radius); end <= endLimit; end++) {
                        if (mask[downstream + end] == 0 || mask[downstream + w + end] == 0) continue;
                        closed = closeGap(mask, closed, w, row, x, end, skip);
                    }
                }
            }
        }
        return closed;
    }

    private static byte[] closeNarrowGaps(byte[] mask, long[] pairs, int width, int height,
            float gap, float maximumGap) {
        byte[] closed = mask;
        int limit = Math.max(1, Math.round(gap * maximumGap));
        for (int y = 1, row = width; y < height - 3; y++, row += width) {
            int lastSkip = Math.min(limit, height - y - 3);
            long current = pairs[y];
            if (current == 0) continue;
            long downstream = pairs[y + 3];
            // For one missing row, a diagonal fills its right-hand column after rounding.
            long middle = (current & downstream) | ((current << 1) & downstream) | (current & (downstream << 1));
            closed = fillColumns(mask, closed, row + width, middle);
            if (lastSkip < 2) continue;

            // With two missing rows, interpolation uses the start column then the end column.
            downstream = pairs[y + 4];
            long firstFill = current & (downstream | (downstream << 1) | (downstream >>> 1));
            long second = downstream & (current | (current << 1) | (current >>> 1));
            closed = fillColumns(mask, closed, row + width, firstFill);
            closed = fillColumns(mask, closed, row + 2 * width, second);
            if (lastSkip < 3) continue;

            for (long starts = current; starts != 0; starts &= starts - 1) {
                int x = Long.numberOfTrailingZeros(starts);
                for (int skip = 3; skip <= lastSkip; skip++) {
                    downstream = pairs[y + skip + 2];
                    if (downstream == 0) continue;
                    int radius = Math.max(1, Math.round((skip + 1) * .3f));
                    int first = Math.max(0, x - radius);
                    int last = Math.min(width - 1, x + radius);
                    long ends = (downstream & (-1L >>> (Long.SIZE - 1 - last))) >>> first;
                    for (; ends != 0; ends &= ends - 1) {
                        int end = first + Long.numberOfTrailingZeros(ends);
                        closed = closeGap(mask, closed, width, row, x, end, skip);
                    }
                }
            }
        }
        return closed;
    }

    private static byte[] fillColumns(byte[] mask, byte[] closed, int row, long columns) {
        for (; columns != 0; columns &= columns - 1) {
            int at = row + Long.numberOfTrailingZeros(columns);
            if (closed[at] != 0) continue;
            if (closed == mask) closed = mask.clone();
            closed[at] = OmrMeasurePostProcessor.CLEF_OR_KEY;
        }
        return closed;
    }

    private static byte[] closeGap(byte[] mask, byte[] closed, int width,
            int row, int x, int end, int skip) {
        // Only fill missing pixels, and keep new ink out of the evidence mask.
        for (int dy = 1, between = row + width; dy <= skip; dy++, between += width) {
            int xx = x + Math.round((end - x) * dy / (float) (skip + 1));
            if (closed[between + xx] != 0) continue;
            if (closed == mask) closed = mask.clone();
            closed[between + xx] = OmrMeasurePostProcessor.CLEF_OR_KEY;
        }
        return closed;
    }

    private static boolean staffRule(byte[] gray, int firstFlank, int left,
            int rightFlank, int lastFlank, int allowedBright) {
        for (int at = firstFlank; at < left; at++)
            if ((gray[at] & 255) > 180 && --allowedBright < 0) return false;
        for (int at = rightFlank; at <= lastFlank; at++)
            if ((gray[at] & 255) > 180 && --allowedBright < 0) return false;
        return true;
    }

    private static boolean ink(byte[] gray, int width, int at, int side) {
        int value = gray[at] & 255;
        if (value <= 180) return true;
        if (value > 245) return false;
        int left = gray[at - side] & 255;
        int right = gray[at + side] & 255;
        if (left <= value || right <= value) return false;

        int above = at - width;
        int below = at + width;
        int threshold = value + (gray[above] & 255) + (gray[below] & 255) + 3;
        return left + (gray[above - side] & 255) + (gray[below - side] & 255) >= threshold
                && right + (gray[above + side] & 255) + (gray[below + side] & 255) >= threshold;
    }
}
