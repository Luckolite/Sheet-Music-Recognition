// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Small local contrast/rule-cleaned windows for retrying an already bounded numeral search. */
final class TupletNumeralInk {
    private static final byte PAPER = (byte) 255;
    private static final byte ERASED_RULE = (byte) 254;

    private TupletNumeralInk() {}

    private static final VarHandle PIXEL_WORD = MethodHandles.byteArrayViewVarHandle(
            long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long LOW_BITS = 0x7f7f7f7f7f7f7f7fL;
    private static final long HIGH_BIT = 0x8080808080808080L;
    private static final long ONE_PER_BYTE = 0x0101010101010101L;

    record Window(byte[] pixels, int width, int height, int left, int top, boolean hasRuleEdges) {}

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
        for (int y = 0; y < hh;) {
            int offset = (top + y) * w + left;
            int repeat = matchingRows(gray, offset, ww, w, hh - y);
            for (int x = offset; x < offset + ww; x++) histogram[gray[x] & 255] += repeat;
            y += repeat;
        }
        return new Crop(gray, w, left, top, ww, hh, percentile(histogram, ww * hh, .85f) - 22, gap, histogram);
    }

    private static int matchingRows(byte[] gray, int offset, int width, int stride, int remaining) {
        int count = 1;
        while (count < remaining) {
            int next = offset + count * stride;
            if (!java.util.Arrays.equals(gray, offset, offset + width, gray, next, next + width)) break;
            count++;
        }
        return count;
    }

    private static final ThreadLocal<RetryMemo> RETRY_MEMO = new ThreadLocal<>();

    static RetryMemo memoizeRetries() {
        var memo = new RetryMemo(RETRY_MEMO.get());
        RETRY_MEMO.set(memo);
        return memo;
    }

    static RetryMemo activeRetryMemo(byte[] gray) {
        var memo = RETRY_MEMO.get();
        if (memo != null) memo.useRaster(gray);
        return memo;
    }

    // Use integer float bits so absent head coordinates also match on Android.
    record RetryGeometry(int stride, int left, int top, int width, int height,
            int firstXBits, int lastXBits, int firstYBits, int lastYBits, int gapBits,
            int headXBits, int headYBits, boolean shortNotes, int number, boolean boundedSecondary) {
        RetryGeometry(int stride, int left, int top, int width, int height,
                float firstX, float lastX, float firstY, float lastY, float gap,
                float headX, float headY, boolean shortNotes, int number, boolean boundedSecondary) {
            this(stride, left, top, width, height, Float.floatToIntBits(firstX),
                    Float.floatToIntBits(lastX), Float.floatToIntBits(firstY),
                    Float.floatToIntBits(lastY), Float.floatToIntBits(gap),
                    Float.floatToIntBits(headX), Float.floatToIntBits(headY),
                    shortNotes, number, boundedSecondary);
        }
    }

    record RetryKey(RetryGeometry geometry, int threshold, boolean ruleEdges) {}

    enum RetryMiss { UNCHANGED_EDGES, POSSIBLE_EDGES }

    /** Bounded, per-read misses that did not consult glyph ownership. */
    static final class RetryMemo implements AutoCloseable {
        private static final int MAX_RETRIES = 8192;
        private final RetryMemo previous;
        private final java.util.LinkedHashMap<RetryKey, RetryMiss> misses =
                new java.util.LinkedHashMap<>(64, .75f, true);
        private byte[] raster;

        private RetryMemo(RetryMemo previous) {
            this.previous = previous;
        }

        private void useRaster(byte[] gray) {
            if (raster != gray) {
                misses.clear();
                raster = gray;
            }
        }

        RetryMiss find(RetryKey key) {
            return misses.get(key);
        }

        void record(RetryKey key, boolean hasRuleEdges) {
            if (misses.size() >= MAX_RETRIES) {
                var oldest = misses.entrySet().iterator();
                oldest.next();
                oldest.remove();
            }
            misses.put(key, hasRuleEdges ? RetryMiss.POSSIBLE_EDGES : RetryMiss.UNCHANGED_EDGES);
        }

        @Override
        public void close() {
            if (previous == null) RETRY_MEMO.remove();
            else RETRY_MEMO.set(previous);
        }
    }

    record Crop(byte[] gray, int sourceWidth, int left, int top, int width, int height,
                int paperLimit, float gap, int[] histogram) {
        int effectiveThreshold(int requested) {
            int threshold = Math.min(requested, paperLimit);
            if (threshold < 100) return -1;
            while (threshold >= 0 && histogram[threshold] == 0) threshold--;
            return threshold;
        }

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
        long thresholdWord = ONE_PER_BYTE * (128 + (threshold & 127));
        boolean upperHalf = threshold >= 128;
        int firstLevelRow = hh - 1;
        boolean anyInk = false, possibleEdges = false;
        for (int y = 0; y < hh;) {
            int source = (top + y) * w + left, target = y * ww;
            int repeat = matchingRows(gray, source, ww, w, hh - y);
            int inkCount = 0;
            int x = 0;
            for (; x <= ww - Long.BYTES; x += Long.BYTES) {
                long grayWord = (long) PIXEL_WORD.get(gray, source + x);
                // The high bit in every minuend prevents borrowing across bytes.
                long ink = (thresholdWord - (grayWord & LOW_BITS)) & HIGH_BIT;
                ink = upperHalf ? ink | (~grayWord & HIGH_BIT) : ink & ~grayWord;
                PIXEL_WORD.set(pixels, target + x, ((HIGH_BIT ^ ink) >>> 7) * 255);
                inkCount += Long.bitCount(ink);
            }
            for (; x < ww; x++) {
                boolean ink = (gray[source + x] & 255) <= threshold;
                pixels[target + x] = ink ? 0 : (byte) 255;
                if (ink) inkCount++;
            }
            for (int filled = 1; filled < repeat;) {
                int copy = Math.min(filled, repeat - filled);
                System.arraycopy(pixels, target, pixels, target + filled * ww, copy * ww);
                filled += copy;
            }
            java.util.Arrays.fill(occupancy, y, y + repeat, inkCount);
            anyInk |= inkCount != 0;
            if (y + repeat > 1 && firstLevelRow == hh - 1 && inkCount >= minimum)
                firstLevelRow = Math.max(1, y);
            if (y + repeat > firstEdgeRow && Math.max(y, firstEdgeRow) < endEdgeRow && inkCount >= edgeMinimum)
                possibleEdges = true;
            y += repeat;
        }
        if (!anyInk) return new Window(pixels, ww, hh, left, top, possibleEdges);
        boolean changed = ruleEdges && possibleEdges
                && levelRuleEdges(pixels, ww, hh, maxEdgeStroke, occupancy, false);
        int maxLevelStroke = Math.max(2, Math.round(gap * .23f));
        for (int y = firstLevelRow; y < hh - 1; y++) {
            if (occupancy[y] < minimum) continue;
            int end = y;
            while (end + 1 < hh - 1 && occupancy[end + 1] >= minimum) end++;
            if (end - y + 1 <= maxLevelStroke) {
                int above = (y - 1) * ww, below = (end + 1) * ww;
                int crossing = pixels[above] != PAPER || pixels[below] != PAPER ? 1 : 0;
                for (int x = 0; x < ww; x++) {
                    int next = x + 1;
                    crossing = (crossing << 1) & 7;
                    if (next < ww && (pixels[above + next] != PAPER || pixels[below + next] != PAPER))
                        crossing |= 1;
                    if (crossing == 0) {
                        for (int at = above + ww + x; at < below; at += ww) {
                            if (pixels[at] != 0) continue;
                            pixels[at] = ERASED_RULE;
                            changed = true;
                        }
                    }
                }
            }
            y = end;
        }
        // Both passes erase level rules using the same original mask. Only an edge
        // erasure still visible after that step could change the sloped-rule input.
        if (!ruleEdges && possibleEdges)
            possibleEdges = levelRuleEdges(pixels, ww, hh, maxEdgeStroke, occupancy, true);
        if (changed) restorePaper(pixels);
        removeSlopedRulesWithThickness(pixels, ww, hh, maxEdgeStroke);
        return new Window(pixels, ww, hh, left, top, possibleEdges);
    }

    private static int levelRuleThickness(float gap) {
        return Math.max(2, Math.round(gap * .30f));
    }

    // A removed byte still encodes the original dark pixel until both level passes end.
    private static void restorePaper(byte[] pixels) {
        int at = 0;
        for (; at <= pixels.length - Long.BYTES; at += Long.BYTES) {
            long word = (long) PIXEL_WORD.get(pixels, at);
            PIXEL_WORD.set(pixels, at, word | ((word & HIGH_BIT) >>> 7));
        }
        for (; at < pixels.length; at++) {
            if (pixels[at] == ERASED_RULE) pixels[at] = PAPER;
        }
    }

    private static boolean levelRuleEdges(byte[] pixels, int w, int h,
            int max, int[] occupancy, boolean checkOnly) {
        int first = max + 2, end = h - max - 2;
        if (first >= end || w <= 2) return false;
        float minimumInk = w * .90f;
        int nonThinLimit = w - (int) Math.ceil(w * .82f);
        int[] firstPixels = new int[w], lastPixels = new int[w];
        boolean changed = false;
        candidate: for (int cy = first; cy < end; cy++) {
            if (occupancy[cy] < minimumInk) continue;
            int nonThin = w - occupancy[cy], row = cy * w;
            if (nonThin > nonThinLimit) continue;
            for (int x = 0; x < w; x++) {
                firstPixels[x] = -1;
                if (pixels[row + x] == PAPER) continue;
                int top = cy, bottom = cy;
                while (top > cy - max && pixels[(top - 1) * w + x] != PAPER) top--;
                int lastThin = top + max - 1;
                while (bottom <= lastThin && pixels[(bottom + 1) * w + x] != PAPER) bottom++;
                if (bottom > lastThin) {
                    if (++nonThin > nonThinLimit) continue candidate;
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
                    continuation = pixels[above + dx] != PAPER || pixels[below + dx] != PAPER;
                if (continuation) continue;
                for (int at = start; at <= stop; at += w) {
                    if (pixels[at] != 0) continue;
                    if (checkOnly) return true;
                    pixels[at] = ERASED_RULE;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static void removeSlopedRules(byte[] pixels, int w, int h, float gap) {
        removeSlopedRulesWithThickness(pixels, w, h, levelRuleThickness(gap));
    }

    private static void removeSlopedRulesWithThickness(byte[] pixels, int w, int h, int max) {
        int first = max + 2, end = h - max - 2;
        if (first >= end || w <= 2) return;
        int missingLimit = w - (int) Math.ceil(w * .90f);
        int lastValidRow = h - 1 - max;
        if (tooFewInkColumns(pixels, w, max, lastValidRow, missingLimit)) return;
        byte[] original = pixels;
        float center = w * .5f;
        int nonThinLimit = w - (int) Math.ceil(w * .82f);
        float[] shifts = new float[w];
        int[] pixelOffsets = new int[w];
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
                pixelOffsets[x] = .5f - Math.abs(shift - rounded) > roundingMargin
                        ? rounded * w + x : Integer.MIN_VALUE;
            }
            int firstValid = max * w, endValid = (lastValidRow + 1) * w;
            // Rounded row offsets are monotone in x. If this interior sample is
            // clipped, more than the allowed number of columns are clipped.
            int lowSample = angle < 0 ? w - 1 - missingLimit : missingLimit;
            int highSample = w - 1 - lowSample;
            candidate: for (int cy = first, row = first * w; cy < end; cy++, row += w) {
                if (projectedPixel(cy, row, w, lowSample, shifts, pixelOffsets) < firstValid
                        || projectedPixel(cy, row, w, highSample, shifts, pixelOffsets) >= endValid)
                    continue;
                int missing = 0, nonThin = 0, spans = 0;
                for (int x = 0; x < w; x++) {
                    int offset = projectedPixel(cy, row, w, x, shifts, pixelOffsets);
                    if (offset < firstValid || offset >= endValid || original[offset] != 0) {
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
                    if (x > 0 && x < w - 1 && offset >= firstValid + w && offset < endValid - w) {
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

    private static int projectedPixel(
            int cy, int row, int width, int x, float[] shifts, int[] pixelOffsets) {
        int offset = pixelOffsets[x];
        return offset == Integer.MIN_VALUE ? Math.round(cy + shifts[x]) * width + x : row + offset;
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
