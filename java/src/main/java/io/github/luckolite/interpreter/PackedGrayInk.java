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

    /** Mutable target pixels for one component scan. Return null for an absent label. */
    static MatchingLabels matchingLabels(byte[] labels, byte target) {
        long repeated = (target & 255) * 0x0101010101010101L;
        int wordCount = (int) ((labels.length + 63L) >>> 6);
        long[] bits = null;
        int at = 0;
        for (; at <= labels.length - Long.BYTES; at += Long.BYTES) {
            long selected = matchingLabelBits(labels, at, repeated);
            if (selected == 0) continue;
            if (bits == null) bits = new long[wordCount];
            bits[at >>> 6] |= selected << at;
        }
        for (; at < labels.length; at++) {
            if (labels[at] != target) continue;
            if (bits == null) bits = new long[wordCount];
            bits[at >>> 6] |= 1L << at;
        }
        return bits == null ? null : new MatchingLabels(bits);
    }

    static final class MatchingLabels {
        private final long[] bits;

        private MatchingLabels(long[] bits) {
            this.bits = bits;
        }

        int nextInk(int from, int end) {
            return nextBit(from, end, 0L);
        }

        int nextGap(int from, int end) {
            return nextBit(from, end, -1L);
        }

        // The exclusive bound keeps a missing neighbor from scanning unrelated rows.
        private int nextBit(int from, int end, long invert) {
            if (from >= end) return end;
            int word = from >>> 6, lastWord = (end - 1) >>> 6;
            long matches = (bits[word] ^ invert) & (-1L << from);
            while (matches == 0 && word < lastWord) matches = bits[++word] ^ invert;
            return matches == 0 ? end : Math.min(end, (word << 6) + Long.numberOfTrailingZeros(matches));
        }

        int runStart(int seed, int rowStart) {
            if (seed == rowStart) return seed;
            int last = seed - 1, word = last >>> 6, firstWord = rowStart >>> 6;
            long gaps = ~bits[word] & (-1L >>> (63 - (last & 63)));
            while (gaps == 0 && word > firstWord) gaps = ~bits[--word];
            return gaps == 0 ? rowStart : Math.max(rowStart, (word << 6) + 64 - Long.numberOfLeadingZeros(gaps));
        }

        void remove(int pixel) {
            bits[pixel >>> 6] &= ~(1L << pixel);
        }

        void removeRun(int first, int last) {
            int startWord = first >>> 6, endWord = last >>> 6;
            long startMask = -1L << first, endMask = -1L >>> (63 - (last & 63));
            if (startWord == endWord) {
                bits[startWord] &= ~(startMask & endMask);
            } else {
                bits[startWord] &= ~startMask;
                java.util.Arrays.fill(bits, startWord + 1, endWord, 0L);
                bits[endWord] &= ~endMask;
            }
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
        return matchingByteBits(mismatch);
    }

    static long matchingLabelBits(byte[] labels, int offset, long packedTarget) {
        return matchingByteBits((long) PIXEL_WORDS.get(labels, offset) ^ packedTarget);
    }

    private static long matchingByteBits(long mismatch) {
        return ((matchingByteLanes(mismatch) >>> 7) * 0x0102040810204080L) >>> 56;
    }

    /** One low bit per byte for either allowed label. */
    static long matchingLabelLanes(byte[] labels, int offset, long first, long second) {
        long values = (long) PIXEL_WORDS.get(labels, offset);
        long matches = matchingByteLanes(values ^ first);
        if (first != second) matches |= matchingByteLanes(values ^ second);
        return matches >>> 7;
    }

    private static long matchingByteLanes(long mismatch) {
        long lowBits = ~HIGH_BITS;
        return ~(((mismatch & lowBits) + lowBits) | mismatch) & HIGH_BITS;
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
