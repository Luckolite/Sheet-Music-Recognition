// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, caller-scoped memoization of successful results for identical ARGB rasters. */
public final class ExactRasterMemo<T> {
    public interface Reader<T> {
        T read() throws Exception;
    }

    private final int capacity;
    private final Map<String, T> entries = new LinkedHashMap<>(16, .75f, true);
    private int hits, misses;

    public ExactRasterMemo(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    public T read(int width, int height, int[] pixels, Reader<T> reader) throws Exception {
        if (width < 1 || height < 1 || (long) width * height != pixels.length)
            throw new IllegalArgumentException("raster size");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        var chunk = ByteBuffer.allocate(16384);
        chunk.putInt(width).putInt(height);
        digest.update(chunk.array(), 0, chunk.position());
        chunk.clear();
        var integers = chunk.asIntBuffer();
        for (int offset = 0; offset < pixels.length; ) {
            int count = Math.min(integers.capacity(), pixels.length - offset);
            integers.clear();
            integers.put(pixels, offset, count);
            digest.update(chunk.array(), 0, count * Integer.BYTES);
            offset += count;
        }
        String key = RasterEvidenceIdentity.hex(digest.digest());
        T found = entries.get(key);
        if (found != null) {
            hits++;
            return found;
        }
        misses++;
        T value = reader.read();
        if (value != null) {
            entries.put(key, value);
            if (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
        }
        return value;
    }

    public int hits() {
        return hits;
    }

    public int misses() {
        return misses;
    }
}
