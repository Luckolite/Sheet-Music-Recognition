// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Small local contrast/rule-cleaned windows for retrying an already bounded numeral search. */
final class TupletNumeralInk {
    private TupletNumeralInk() {}

    record Window(byte[] pixels, int width, int height, int left, int top) {}

    /** A binary-looking paper stain is not a printed numeral. Use original gray
     * values, not the normalized mask, to require local glyph-to-paper contrast. */
    static boolean hasGlyphContrast(
            byte[] gray, int w, int h, int left, int top, int right, int bottom, float gap) {
        if (gray == null
                || left < 0
                || top < 0
                || right >= w
                || bottom >= h
                || left > right
                || top > bottom) return false;
        int margin = Math.max(3, Math.round(gap * .45f));
        int outerLeft = Math.max(0, left - margin), outerRight = Math.min(w - 1, right + margin);
        int outerTop = Math.max(0, top - margin), outerBottom = Math.min(h - 1, bottom + margin);
        int inside = (right - left + 1) * (bottom - top + 1);
        int outside = (outerRight - outerLeft + 1) * (outerBottom - outerTop + 1) - inside;
        if (inside < 6 || outside < 6) return false;

        int[] histogram = new int[256];
        for (int y = top; y <= bottom; y++) {
            int row = y * w;
            for (int x = left; x <= right; x++) histogram[gray[row + x] & 255]++;
        }
        int paperMinimum = percentile(histogram, inside, .10f) + 32;
        if (paperMinimum > 255) return false;
        // The paper percentile clears the contrast threshold iff fewer than
        // its required number of samples lie below that threshold.
        float paperTarget = outside * .85f;
        int darker = 0;
        for (int y = outerTop; y <= outerBottom; y++) {
            int row = y * w;
            boolean crossesGlyph = y >= top && y <= bottom;
            int leftEnd = crossesGlyph ? left - 1 : outerRight;
            for (int x = outerLeft; x <= leftEnd; x++)
                if ((gray[row + x] & 255) < paperMinimum && ++darker >= paperTarget) return false;
            if (crossesGlyph)
                for (int x = right + 1; x <= outerRight; x++)
                    if ((gray[row + x] & 255) < paperMinimum && ++darker >= paperTarget) return false;
        }
        return true;
    }

    private static int percentile(int[] histogram, int count, float fraction) {
        int sum = 0;
        for (int i = 0; i < histogram.length; i++) {
            sum += histogram[i];
            if (sum >= count * fraction) return i;
        }
        return 255;
    }

    static Window window(
            byte[] gray,
            int w,
            int h,
            float x1,
            float x3,
            float y1,
            float y3,
            float gap,
            int requested) {
        return window(gray, w, h, x1, x3, y1, y3, gap, requested, false);
    }

    static Window ruleEdgesWindow(
            byte[] gray,
            int w,
            int h,
            float x1,
            float x3,
            float y1,
            float y3,
            float gap,
            int requested) {
        return window(gray, w, h, x1, x3, y1, y3, gap, requested, true);
    }

    private static Window window(
            byte[] gray,
            int w,
            int h,
            float x1,
            float x3,
            float y1,
            float y3,
            float gap,
            int requested,
            boolean ruleEdges) {
        var crop = crop(gray, w, h, x1, x3, y1, y3, gap);
        return crop == null ? null : crop.window(requested, ruleEdges);
    }

    static Crop crop(
            byte[] gray, int w, int h, float x1, float x3, float y1, float y3, float gap) {
        if (gray == null || gap < 4) return null;
        int left = Math.max(0, Math.round(x1 - gap * 2)),
                right = Math.min(w - 1, Math.round(x3 + gap * 2));
        int top = Math.max(0, Math.round(y1 - gap * 7.5f)),
                bottom = Math.min(h - 1, Math.round(y3 + gap * 7.5f));
        int ww = right - left + 1, hh = bottom - top + 1;
        if (ww < 3 || hh < 3) return null;
        int[] histogram = new int[256];
        for (int y = top; y <= bottom; y++) {
            int offset = y * w;
            for (int x = left; x <= right; x++) histogram[gray[offset + x] & 255]++;
        }
        return new Crop(gray, w, left, top, ww, hh, percentile(histogram, ww * hh, .85f) - 22, gap);
    }

    record Crop(byte[] gray, int sourceWidth, int left, int top, int width, int height,
                int paperLimit, float gap) {
        Window window(int requested, boolean ruleEdges) {
            return cleanWindow(this, Math.min(requested, paperLimit), ruleEdges);
        }
    }

    private static Window cleanWindow(Crop crop, int threshold, boolean ruleEdges) {
        if (threshold < 100) return null;
        int ww = crop.width(), hh = crop.height();
        byte[] gray = crop.gray();
        int w = crop.sourceWidth(), left = crop.left(), top = crop.top();
        float gap = crop.gap();
        byte[] pixels = new byte[ww * hh];
        int[] occupancy = new int[hh];
        for (int y = 0; y < hh; y++) {
            int source = (y + top) * w + left, target = y * ww;
            for (int x = 0; x < ww; x++) {
                boolean ink = (gray[source + x] & 255) <= threshold;
                pixels[target + x] = ink ? 0 : (byte) 255;
                if (ink) occupancy[y]++;
            }
        }
        byte[] original = ruleEdges ? pixels.clone() : null;
        if (ruleEdges) removeLevelRuleEdges(pixels, original, ww, hh, gap);
        for (int y = 1; y < hh - 1; y++) {
            if (occupancy[y] < ww * .83f) continue;
            int end = y;
            while (end + 1 < hh - 1 && occupancy[end + 1] >= ww * .83f) end++;
            if (end - y + 1 <= Math.max(2, Math.round(gap * .23f))) {
                if (original == null) original = pixels.clone();
                for (int x = 0; x < ww; x++) {
                    boolean above = false, below = false;
                    for (int dx = -1; dx <= 1; dx++)
                        if (x + dx >= 0 && x + dx < ww) {
                            above |= original[(y - 1) * ww + x + dx] == 0;
                            below |= original[(end + 1) * ww + x + dx] == 0;
                        }
                    boolean crossing = above || below;
                    if (!crossing)
                        for (int yy = y; yy <= end; yy++) pixels[yy * ww + x] = (byte) 255;
                }
            }
            y = end;
        }
        removeSlopedRules(pixels, ww, hh, gap);
        return new Window(pixels, ww, hh, left, top);
    }

    private static void removeLevelRuleEdges(byte[] pixels, byte[] original, int w, int h, float gap) {
        int max = Math.max(2, Math.round(gap * .30f));
        for (int cy = max + 2; cy < h - max - 2; cy++) {
            int valid = 0, thin = 0;
            for (int x = 0; x < w; x++) {
                if (original[cy * w + x] != 0) continue;
                valid++;
                int top = cy, bottom = cy;
                while (top > cy - max && original[(top - 1) * w + x] == 0) top--;
                while (bottom < cy + max && original[(bottom + 1) * w + x] == 0) bottom++;
                if (bottom - top + 1 <= max) thin++;
            }
            if (valid < w * .90f || thin < w * .82f) continue;
            for (int x = 1; x < w - 1; x++) {
                if (original[cy * w + x] != 0) continue;
                int top = cy, bottom = cy;
                while (top > cy - max && original[(top - 1) * w + x] == 0) top--;
                while (bottom < cy + max && original[(bottom + 1) * w + x] == 0) bottom++;
                if (bottom - top + 1 > max) continue;
                boolean continuation = false;
                for (int dx = -1; dx <= 1; dx++)
                    continuation |=
                            original[(top - 1) * w + x + dx] == 0
                                    || original[(bottom + 1) * w + x + dx] == 0;
                if (!continuation)
                    for (int yy = top; yy <= bottom; yy++) pixels[yy * w + x] = (byte) 255;
            }
        }
    }

    private static void removeSlopedRules(byte[] pixels, int w, int h, float gap) {
        int max = Math.max(2, Math.round(gap * .30f));
        int first = max + 2, end = h - max - 2;
        if (first >= end) return;
        byte[] original = pixels.clone();
        float center = w * .5f;
        int missingLimit = w - (int) Math.ceil(w * .90f);
        int nonThinLimit = w - (int) Math.ceil(w * .82f);
        float[] shifts = new float[w];
        int[] tops = new int[w], bottoms = new int[w];
        for (int angle = -4; angle <= 4; angle++) {
            if (angle == 0) continue;
            float slope = angle * .025f;
            for (int x = 0; x < w; x++) shifts[x] = slope * (x - center);
            for (int cy = first; cy < end; cy++) {
                int missing = 0, nonThin = 0;
                for (int x = 0; x < w; x++) {
                    tops[x] = -1;
                    int y = Math.round(cy + shifts[x]);
                    int offset = y * w + x;
                    if (y - max < 0 || y + max >= h || original[offset] != 0) {
                        missing++;
                        nonThin++;
                        if (missing > missingLimit || nonThin > nonThinLimit) break;
                        continue;
                    }
                    int top = y;
                    while (top > y - max && original[(top - 1) * w + x] == 0) top--;
                    int bottom = y, lastThin = top + max - 1;
                    while (bottom <= lastThin && original[(bottom + 1) * w + x] == 0)
                        bottom++;
                    if (bottom > lastThin) {
                        if (++nonThin > nonThinLimit) break;
                        continue;
                    }
                    if (y - max > 0 && y + max + 1 < h) {
                        tops[x] = top;
                        bottoms[x] = bottom;
                    }
                }
                if (missing > missingLimit || nonThin > nonThinLimit) continue;
                for (int x = 1; x < w - 1; x++) {
                    int top = tops[x], bottom = bottoms[x];
                    if (top < 0) continue;
                    int aboveRow = (top - 1) * w + x, belowRow = (bottom + 1) * w + x;
                    boolean above = original[aboveRow - 1] == 0 || original[aboveRow] == 0
                            || original[aboveRow + 1] == 0;
                    boolean below = original[belowRow - 1] == 0 || original[belowRow] == 0
                            || original[belowRow + 1] == 0;
                    if (!above || !below)
                        for (int yy = top; yy <= bottom; yy++) pixels[yy * w + x] = (byte) 255;
                }
            }
        }
    }
}
