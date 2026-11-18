// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Raster-order ink bits for printed grayscale marks. */
final class PackedGrayInk {
    private static final java.lang.invoke.VarHandle PIXEL_WORDS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                    long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);
    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long BRIGHT_BITS = 0xc0c0c0c0c0c0c0c0L;

    private PackedGrayInk() {}

    // Callers use cutoffs in the supported interval [128, 192].
    static long[] classify(byte[] gray, int offset, int size, int threshold) {
        long[] words = new long[(int) ((size + 63L) >>> 6)];
        fill(gray, offset, size, threshold, words, 0);
        return words;
    }

    static void fill(byte[] gray, int offset, int size, int threshold, long[] words, int at) {
        int p = 0;
        long bias = 0x0101010101010101L * (threshold - 128);
        if (threshold >= 128 && threshold <= 192) {
            for (; p <= size - Long.SIZE; at++, p += Long.SIZE) {
                int base = offset + p;
                words[at] = thirtyTwoInkBits(gray, base, bias) | (thirtyTwoInkBits(gray, base + 32, bias) << 32);
            }
        }
        while (p < size) {
            int end = p + Math.min(size - p, 64);
            long bits = 0;
            if (threshold >= 128 && threshold <= 255)
                for (; p <= end - 8; p += 8)
                    bits |= inkBits((long) PIXEL_WORDS.get(gray, offset + p), bias) << p;
            if (threshold > 0 && threshold < 128) {
                long lowBias = 0x0101010101010101L * threshold;
                for (; p <= end - 8; p += 8) {
                    long tone = (long) PIXEL_WORDS.get(gray, offset + p);
                    // Only low-half bytes can pass. Set their high bit before subtraction to prevent borrowing.
                    long dark = ~(tone | ((tone | HIGH_BITS) - lowBias)) & HIGH_BITS;
                    bits |= (((dark >>> 7) * 0x0102040810204080L) >>> 56) << p;
                }
            }
            for (; p < end; p++) if ((gray[offset + p] & 255) < threshold) bits |= 1L << p;
            words[at++] = bits;
        }
    }

    static void fillSymbols(byte[] labels, int offset, int size, long[] words, int at) {
        int p = 0;
        while (p < size) {
            int end = p + Math.min(64, size - p);
            long bits = 0;
            for (; p <= end - 32; p += 32) {
                int start = offset + p;
                long a = (long) PIXEL_WORDS.get(labels, start);
                long b = (long) PIXEL_WORDS.get(labels, start + 8);
                long c = (long) PIXEL_WORDS.get(labels, start + 16);
                long d = (long) PIXEL_WORDS.get(labels, start + 24);
                if ((a | b | c | d) != 0)
                    bits |= (symbolBits(a) | symbolBits(b) << 8 | symbolBits(c) << 16 | symbolBits(d) << 24) << p;
            }
            for (; p <= end - 8; p += 8)
                bits |= symbolBits((long) PIXEL_WORDS.get(labels, offset + p)) << p;
            for (; p < end; p++)
                if (labels[offset + p] == OmrMeasurePostProcessor.SYMBOL) bits |= 1L << p;
            words[at++] = bits;
        }
    }

    private static long symbolBits(long labels) {
        long mismatch = labels ^ (0x0101010101010101L * OmrMeasurePostProcessor.SYMBOL);
        long lowBits = ~HIGH_BITS;
        long selected = ~(((mismatch & lowBits) + lowBits) | mismatch) & HIGH_BITS;
        return ((selected >>> 7) * 0x0102040810204080L) >>> 56;
    }

    // One high bit per grayscale sample in the rest contrast interval [170, 205).
    static long restContrastLanes(byte[] gray, int at) {
        long tone = (long) PIXEL_WORDS.get(gray, at);
        long high = tone | HIGH_BITS;
        return tone & (high - 0x2a2a2a2a2a2a2a2aL)
                & ~(high - 0x4d4d4d4d4d4d4d4dL) & HIGH_BITS;
    }

    static boolean isSlideRuleRow(byte[] gray, int start, int width) {
        final long bias = 0x0101010101010101L * (150 - 128);
        int required = (int) (width * .45) + 1;
        int x = 0;
        for (; x <= width - 32; x += 32) {
            required -= Long.bitCount(thirtyTwoInkBits(gray, start + x, bias));
            if (required <= 0) return true;
            if (required > width - x - 32) return false;
        }
        for (; x < width; x++)
            if ((gray[start + x] & 255) < 150 && --required == 0) return true;
        return false;
    }

    private static long thirtyTwoInkBits(byte[] gray, int offset, long bias) {
        long a = (long) PIXEL_WORDS.get(gray, offset);
        long b = (long) PIXEL_WORDS.get(gray, offset + 8);
        long c = (long) PIXEL_WORDS.get(gray, offset + 16);
        long d = (long) PIXEL_WORDS.get(gray, offset + 24);
        // Every pixel with both high bits set is brighter than the ink cutoff.
        if ((a & b & c & d & BRIGHT_BITS) == BRIGHT_BITS) return 0;
        return inkBits(a, bias) | inkBits(b, bias) << 8 | inkBits(c, bias) << 16 | inkBits(d, bias) << 24;
    }

    private static long inkBits(long tone, long bias) {
        long dark = ~(((tone | HIGH_BITS) - bias) & tone) & HIGH_BITS;
        return ((dark >>> 7) * 0x0102040810204080L) >>> 56;
    }
}
