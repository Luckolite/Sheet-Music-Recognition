// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Eight-connected dark components in raster seed order, with exclusive right and bottom bounds. */
final class PrintedInkComponents {
    private static final VarHandle WORD =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final long HIGH = 0x8080808080808080L;
    private byte[] ink = new byte[0];
    private final ShadedComponentRuns runs = new ShadedComponentRuns(128);

    interface Visitor {
        boolean visit(int left, int top, int right, int bottom, int count);
    }

    /** Stops scanning when the visitor returns false. */
    boolean scan(byte[] gray, int width, int left, int top, int right, int bottom,
            int threshold, Visitor visitor) {
        int stride = right - left + 2;
        int size = stride * (bottom - top + 2);
        if (ink.length < size) ink = new byte[size];
        // Empty margins keep diagonal neighbor scans inside the crop, even on scratch reuse.
        Arrays.fill(ink, 0, stride, (byte) 0);
        // Pack eight pixels when the threshold is in the light half of the grayscale range.
        long bias = 0x0101010101010101L * (threshold + 1 - 128);
        for (int y = top; y < bottom; y++) {
            int at = (y - top + 1) * stride;
            ink[at++] = 0;
            int p = y * width + left, end = y * width + right;
            int packedEnd = threshold >= 128 && threshold < 255 ? end - Long.BYTES : p - 1;
            for (; p <= packedEnd; p += Long.BYTES, at += Long.BYTES) {
                long tone = (long) WORD.get(gray, p);
                long dark = ~(((tone | HIGH) - bias) & tone) & HIGH;
                WORD.set(ink, at, dark >>> 7);
            }
            for (; p < end; p++)
                ink[at++] = (byte) ((gray[p] & 255) <= threshold ? 1 : 0);
            ink[at] = 0;
        }
        Arrays.fill(ink, size - stride, size, (byte) 0);
        for (int seed = stride; seed < size - stride; seed++) {
            if (ink[seed] == 0) {
                if (seed <= size - Long.BYTES && (long) WORD.get(ink, seed) == 0)
                    seed += Long.BYTES - 1;
                continue;
            }
            int queued = runs.enqueue(ink, seed, 1, (byte) 0, 0);
            int minX = stride, maxX = 0, minY = seed / stride, maxY = minY, count = 0;
            for (int i = 0; i < queued; i += 2) {
                int first = runs.queue[i], last = runs.queue[i + 1];
                int y = first / stride, x = first - y * stride;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + (last - first));
                maxY = Math.max(maxY, y);
                count += last - first + 1;
                for (int offset = -stride; offset <= stride; offset += 2 * stride) {
                    int end = last + offset + 1;
                    for (int p = first + offset - 1; p <= end; p++) {
                        if (ink[p] == 0) continue;
                        queued = runs.enqueue(ink, p, 1, (byte) 0, queued);
                        p = runs.queue[queued - 1];
                    }
                }
            }
            if (!visitor.visit(left + minX - 1, top + minY - 1, left + maxX, top + maxY, count))
                return false;
        }
        return true;
    }
}
