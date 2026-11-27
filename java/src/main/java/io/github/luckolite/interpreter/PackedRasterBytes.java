// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Uses JVM array views for raster bytes used by the shared beam threshold. */
final class PackedRasterBytes {
    private static final VarHandle WORDS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private PackedRasterBytes() {}

    static long readLittleEndian(byte[] raster, int offset) {
        return (long) WORDS.get(raster, offset);
    }

    static boolean sameRange(byte[] raster, int first, int second, int length) {
        return Arrays.mismatch(raster, first, first + length, raster, second, second + length) < 0;
    }
}
