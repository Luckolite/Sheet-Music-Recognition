// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Local-background evidence for existing sharp-shape verification, never a classifier alone. */
final class FaintSharpInk {
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
        for (int y = top, row = top * width, dest = 0;
                y <= bottom; y++, row += width, dest += w) {
            boolean rule = staffRule(gray, row + firstFlank, row + left,
                    row + right + 1, row + lastFlank, allowedBright);
            for (int x = 0, at = row + left; x < w; x++, at++) {
                if (!ink(gray, width, at, side)) continue;
                if (rule
                        && (!ink(gray, width, at - probeStride, side)
                                || !ink(gray, width, at + probeStride, side))) continue;
                mask[dest + x] = OmrMeasurePostProcessor.CLEF_OR_KEY;
            }
        }
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
                        // Only fill missing pixels, and keep new ink out of the evidence mask.
                        for (int dy = 1, between = row + w; dy <= skip; dy++, between += w) {
                            int xx = x + Math.round((end - x) * dy / (float) (skip + 1));
                            if (closed[between + xx] != 0) continue;
                            if (closed == mask) closed = mask.clone();
                            closed[between + xx] = OmrMeasurePostProcessor.CLEF_OR_KEY;
                        }
                    }
                }
            }
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
