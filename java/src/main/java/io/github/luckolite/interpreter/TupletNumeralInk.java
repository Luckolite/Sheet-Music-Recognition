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
        float target = count * fraction;
        for (int i = 0; i < histogram.length; i++) {
            sum += histogram[i];
            if (sum >= target) return i;
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
        float minimum = ww * .83f, edgeMinimum = ww * .90f;
        int maxEdgeStroke = levelRuleThickness(gap);
        int firstEdgeRow = maxEdgeStroke + 2, endEdgeRow = hh - maxEdgeStroke - 2;
        byte[] pixels = new byte[ww * hh];
        int[] occupancy = new int[hh];
        int inkTotal = 0, firstLevelRow = hh - 1;
        boolean possibleEdges = false;
        for (int y = 0, source = top * w + left, target = 0;
                y < hh; y++, source += w, target += ww) {
            int inkCount = 0;
            for (int x = 0; x < ww; x++) {
                boolean ink = (gray[source + x] & 255) <= threshold;
                pixels[target + x] = ink ? 0 : (byte) 255;
                if (ink) inkCount++;
            }
            occupancy[y] = inkCount;
            inkTotal += inkCount;
            if (y > 0 && firstLevelRow == hh - 1 && inkCount >= minimum)
                firstLevelRow = y;
            if (ruleEdges && y >= firstEdgeRow && y < endEdgeRow && inkCount >= edgeMinimum)
                possibleEdges = true;
        }
        if (inkTotal == 0) return new Window(pixels, ww, hh, left, top);
        byte[] original = possibleEdges ? pixels.clone() : null;
        if (possibleEdges) removeLevelRuleEdges(pixels, original, ww, hh, gap);
        int maxLevelStroke = Math.max(2, Math.round(gap * .23f));
        for (int y = firstLevelRow; y < hh - 1; y++) {
            if (occupancy[y] < minimum) continue;
            int end = y;
            while (end + 1 < hh - 1 && occupancy[end + 1] >= minimum) end++;
            if (end - y + 1 <= maxLevelStroke) {
                if (original == null) original = pixels.clone();
                int above = (y - 1) * ww, below = (end + 1) * ww;
                for (int x = 0; x < ww; x++) {
                    boolean crossing = false;
                    int first = Math.max(0, x - 1), last = Math.min(ww - 1, x + 1);
                    for (int xx = first; xx <= last && !crossing; xx++)
                        crossing = original[above + xx] == 0 || original[below + xx] == 0;
                    if (!crossing)
                        for (int at = above + ww + x; at < below; at += ww) pixels[at] = (byte) 255;
                }
            }
            y = end;
        }
        removeSlopedRules(pixels, ww, hh, gap);
        return new Window(pixels, ww, hh, left, top);
    }

    private static int levelRuleThickness(float gap) {
        return Math.max(2, Math.round(gap * .30f));
    }

    private static void removeLevelRuleEdges(byte[] pixels, byte[] original, int w, int h, float gap) {
        int max = levelRuleThickness(gap);
        int first = max + 2, end = h - max - 2;
        if (first >= end || w <= 2) return;
        float minimumInk = w * .90f, minimumThin = w * .82f;
        int[] firstPixels = new int[w], lastPixels = new int[w];
        candidate: for (int cy = first; cy < end; cy++) {
            int missing = 0, nonThin = 0, row = cy * w;
            for (int x = 0; x < w; x++) {
                firstPixels[x] = -1;
                if (original[row + x] != 0) {
                    if (w - ++missing < minimumInk || w - ++nonThin < minimumThin)
                        continue candidate;
                    continue;
                }
                int top = cy, bottom = cy;
                while (top > cy - max && original[(top - 1) * w + x] == 0) top--;
                int lastThin = top + max - 1;
                while (bottom <= lastThin && original[(bottom + 1) * w + x] == 0) bottom++;
                if (bottom > lastThin) {
                    if (w - ++nonThin < minimumThin) continue candidate;
                    continue;
                }
                firstPixels[x] = top * w + x;
                lastPixels[x] = bottom * w + x;
            }
            for (int x = 1; x < w - 1; x++) {
                int start = firstPixels[x], stop = lastPixels[x];
                if (start < 0) continue;
                int above = start - w, below = stop + w;
                boolean continuation = false;
                for (int dx = -1; dx <= 1 && !continuation; dx++)
                    continuation = original[above + dx] == 0 || original[below + dx] == 0;
                if (!continuation)
                    for (int at = start; at <= stop; at += w) pixels[at] = (byte) 255;
            }
        }
    }

    private static void removeSlopedRules(byte[] pixels, int w, int h, float gap) {
        int max = levelRuleThickness(gap);
        int first = max + 2, end = h - max - 2;
        if (first >= end || w <= 2) return;
        int missingLimit = w - (int) Math.ceil(w * .90f);
        int lastValidRow = h - 1 - max;
        if (tooFewInkColumns(pixels, w, max, lastValidRow, missingLimit)) return;
        byte[] original = pixels;
        float center = w * .5f;
        int nonThinLimit = w - (int) Math.ceil(w * .82f);
        float[] shifts = new float[w];
        int[] rowOffsets = new int[w];
        int[] abovePixels = new int[w - 2], belowPixels = new int[w - 2];
        // An addition cannot move a shift across a half-pixel boundary by more
        // than this rounding bound over the entire candidate row range.
        float roundingMargin = Math.ulp(end + w * .1f);
        int maxStrokePixels = max * w, afterMaxStroke = maxStrokePixels + w;
        for (int angle = -4; angle <= 4; angle++) {
            if (angle == 0) continue;
            float slope = angle * .025f;
            for (int x = 0; x < w; x++) {
                float shift = shifts[x] = slope * (x - center);
                int rounded = Math.round(shift);
                rowOffsets[x] = .5f - Math.abs(shift - rounded) > roundingMargin
                        ? rounded : Integer.MIN_VALUE;
            }
            candidate: for (int cy = first; cy < end; cy++) {
                int missing = 0, nonThin = 0, spans = 0;
                for (int x = 0; x < w; x++) {
                    int rowOffset = rowOffsets[x];
                    int y = rowOffset == Integer.MIN_VALUE
                            ? Math.round(cy + shifts[x]) : cy + rowOffset;
                    int offset = y * w + x;
                    if (y < max || y > lastValidRow || original[offset] != 0) {
                        if (++missing > missingLimit || ++nonThin > nonThinLimit)
                            continue candidate;
                        continue;
                    }
                    int above = offset - w, firstProbe = offset - maxStrokePixels;
                    while (above >= firstProbe && original[above] == 0) above -= w;
                    int afterThin = above + afterMaxStroke;
                    int below = offset + w;
                    while (below <= afterThin && original[below] == 0) below += w;
                    if (below > afterThin) {
                        if (++nonThin > nonThinLimit) continue candidate;
                        continue;
                    }
                    if (x > 0 && x < w - 1 && y > max && y < lastValidRow) {
                        abovePixels[spans] = above;
                        belowPixels[spans++] = below;
                    }
                }
                for (int i = 0; i < spans; i++) {
                    int aboveRow = abovePixels[i], belowRow = belowPixels[i];
                    boolean above = original[aboveRow - 1] == 0 || original[aboveRow] == 0
                            || original[aboveRow + 1] == 0;
                    if (!above || (original[belowRow - 1] != 0 && original[belowRow] != 0
                            && original[belowRow + 1] != 0)) {
                        if (original == pixels) original = pixels.clone();
                        for (int at = aboveRow + w; at < belowRow; at += w)
                            pixels[at] = (byte) 255;
                    }
                }
            }
        }
    }

    private static boolean tooFewInkColumns(byte[] pixels, int width, int top, int bottom,
            int missingLimit) {
        int first = top * width, last = bottom * width;
        int needed = width - missingLimit;
        for (int x = 0; x < width; x++) {
            int end = last + x, at = first + x;
            while (at <= end && pixels[at] != 0) at += width;
            if (at <= end) {
                if (--needed == 0) return false;
            } else if (--missingLimit < 0) return true;
        }
        return false;
    }
}
