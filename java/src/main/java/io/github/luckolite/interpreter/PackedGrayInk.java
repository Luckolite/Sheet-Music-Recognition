// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Raster-order ink bits for the slide and octave cutoffs (150 and 165). */
final class PackedGrayInk {
    private static final java.lang.invoke.VarHandle PIXEL_WORDS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                    long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);
    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long BRIGHT_BITS = 0xc0c0c0c0c0c0c0c0L;

    private PackedGrayInk() {}

    // Both callers use cutoffs in the supported interval [128, 192].
    static long[] classify(byte[] gray, int offset, int size, int threshold) {
        long bias = 0x0101010101010101L * (threshold - 128);
        long[] words = new long[(int) ((size + 63L) >>> 6)];
        int p = 0;
        for (int i = 0; p <= size - Long.SIZE; i++, p += Long.SIZE) {
            int base = offset + p;
            words[i] = thirtyTwoInkBits(gray, base, bias) | (thirtyTwoInkBits(gray, base + 32, bias) << 32);
        }
        for (; p <= size - Long.BYTES; p += Long.BYTES)
            words[p >>> 6] |= inkBits((long) PIXEL_WORDS.get(gray, offset + p), bias) << p;
        for (; p < size; p++)
            if ((gray[offset + p] & 255) < threshold) words[p >>> 6] |= 1L << p;
        return words;
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
