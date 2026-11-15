// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Propose predominantly faded glyphs; ordinary dark candidates keep their existing bounds. */
final class FaintDynamicGlyphComponents {
    private static final java.lang.invoke.VarHandle PIXELS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                    long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);
    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long BYTE_ONES = 0x0101010101010101L;

    private static long atLeast(long tones, long bias) {
        return ((tones | HIGH_BITS) - bias) & tones & HIGH_BITS;
    }

    record Bounds(int left, int top, int right, int bottom, int pixels) {}

    static List<Bounds> find(byte[] gray, int width, int height, boolean[] seen, int[] queue) {
        var result = new ArrayList<Bounds>();
        if (!hasFadedTone(gray)) {
            // With no tone between the dark and broad gates, every component is dark.
            for (int p = 0; p < gray.length; p++) seen[p] = (gray[p] & 255) < 145;
            Arrays.fill(seen, gray.length, seen.length, false);
            return result;
        }
        boolean hasFaintInk = findAt(gray, width, height, seen, queue, 205, .2f, result);
        if (hasFaintInk) findAt(gray, width, height, seen, queue, 185, .65f, result);
        return result;
    }

    private static boolean hasFadedTone(byte[] gray) {
        int p = 0;
        for (; p <= gray.length - Long.BYTES; p += Long.BYTES) {
            long tones = (long) PIXELS.get(gray, p);
            if ((atLeast(tones, BYTE_ONES * (145 - 128))
                    & ~atLeast(tones, BYTE_ONES * (205 - 128))) != 0) return true;
        }
        for (; p < gray.length; p++) {
            int tone = gray[p] & 255;
            if (tone >= 145 && tone < 205) return true;
        }
        return false;
    }

    private static boolean findAt(
            byte[] gray,
            int width,
            int height,
            boolean[] seen,
            int[] queue,
            int threshold,
            float darkFraction,
            List<Bounds> result) {
        Arrays.fill(seen, false);
        boolean hasFaintInk = false;
        boolean narrow = threshold == 185;
        int broadCount = result.size(), firstBroad = 0, firstNarrow = broadCount;
        long bias = BYTE_ONES * (threshold - 128);
        for (int p = 0; p < gray.length; p++) {
            if ((gray[p] & 255) >= threshold) {
                if (p > gray.length - Long.BYTES) continue;
                long light = atLeast((long) PIXELS.get(gray, p), bias);
                int skip = Long.numberOfTrailingZeros(HIGH_BITS ^ light) / Byte.SIZE;
                p += skip;
                if (skip == Long.BYTES) {
                    p--;
                    continue;
                }
            }
            if (seen[p]) continue;
            int read = 0, queued = 1, count = 0, dark = 0;
            queue[0] = p;
            seen[p] = true;
            int top = p / width, bottom = top, left = width, right = 0;
            while (read < queued) {
                int seed = queue[read++], y = seed / width, row = y * width;
                int first = seed, last = seed;
                int tone = gray[seed] & 255;
                if (tone < 145) dark++;
                else if (!narrow && tone < 185) hasFaintInk = true;
                while (first > row && !seen[first - 1]) {
                    tone = gray[first - 1] & 255;
                    if (tone >= threshold) break;
                    first--;
                    if (tone < 145) dark++;
                    else if (!narrow && tone < 185) hasFaintInk = true;
                }
                while (last < row + width - 1 && !seen[last + 1]) {
                    tone = gray[last + 1] & 255;
                    if (tone >= threshold) break;
                    last++;
                    if (tone < 145) dark++;
                    else if (!narrow && tone < 185) hasFaintInk = true;
                }
                Arrays.fill(seen, first, last + 1, true);
                left = Math.min(left, first - row);
                right = Math.max(right, last - row);
                bottom = Math.max(bottom, y);
                count += last - first + 1;
                int neighborFirst = Math.max(row, first - 1);
                int neighborLast = Math.min(row + width - 1, last + 1);
                for (int step = -1; step <= 1; step += 2) {
                    if (y + step < 0 || y + step >= height) continue;
                    int end = neighborLast + step * width;
                    for (int next = neighborFirst + step * width; next <= end; next++) {
                        if (seen[next] || (gray[next] & 255) >= threshold) continue;
                        seen[next] = true;
                        queue[queued++] = next;
                        while (next < end && !seen[next + 1] && (gray[next + 1] & 255) < threshold)
                            next++;
                    }
                }
            }
            if (count < 3 || dark >= count * darkFraction) continue;
            if (narrow) {
                // Seeds start components in top-to-bottom order in each pass.
                // Earlier bounds ending at or before this top cannot cover this or later seeds.
                while (firstBroad < broadCount && result.get(firstBroad).bottom() <= top)
                    firstBroad++;
                while (firstNarrow < result.size() && result.get(firstNarrow).bottom() <= top)
                    firstNarrow++;
                if (covered(result, firstBroad, broadCount, left, top, right + 1, bottom + 1)
                        || covered(result, firstNarrow, result.size(), left, top, right + 1, bottom + 1))
                    continue;
            }
            result.add(new Bounds(left, top, right + 1, bottom + 1, count));
        }
        return hasFaintInk;
    }

    private static boolean covered(
            List<Bounds> result, int first, int end, int left, int top, int right, int bottom) {
        for (int i = first; i < end; i++) {
            var prior = result.get(i);
            if (prior.top() > top) break;
            if (prior.left() <= left
                    && prior.right() >= right && prior.bottom() >= bottom) return true;
        }
        return false;
    }
}
