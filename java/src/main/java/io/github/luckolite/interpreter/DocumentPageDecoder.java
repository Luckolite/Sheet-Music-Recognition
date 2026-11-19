// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/** Private, document-scoped pipe used by the Python PDF reader. */
public final class DocumentPageDecoder {
    private static final ThreadLocal<PreparedGeometry> PREPARED = new ThreadLocal<>();

    private DocumentPageDecoder() {}

    record PreparedGeometry(byte[] labels, byte[] gray, byte[] corrected, int width, int height,
            SelectedMeasureOrigins origins, NativeDecoderStages.Geometry geometry) {
        byte[] geometryGray() {
            return corrected == null ? gray : corrected;
        }
    }

    static PreparedGeometry matchingGeometry(
            byte[] labels, byte[] gray, byte[] corrected, int width, int height) {
        var prepared = PREPARED.get();
        return prepared != null
                        && width == prepared.width()
                        && height == prepared.height()
                        && Arrays.equals(labels, prepared.labels())
                        && Arrays.equals(gray, prepared.gray())
                        && Arrays.equals(corrected, prepared.corrected())
                ? prepared : null;
    }

    private static PreparedGeometry prepareGeometry(String raster, String correctedRaster) {
        try (var stream = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(Path.of(raster)), 32 * 1024)))) {
            if (stream.readInt() != 0x52535031) return null;
            int width = stream.readInt(), height = stream.readInt();
            long size = (long) width * height;
            if (width < 1 || height < 1 || size > 500_000) return null;
            byte[] labels = new byte[(int) size], gray = new byte[(int) size];
            stream.readFully(labels);
            stream.readFully(gray);
            if (stream.read() != -1) return null;
            for (byte label : labels)
                if (label < 0 || label > 5) return null;
            byte[] corrected = null;
            if (!correctedRaster.isEmpty()) {
                try (var correction = Files.newInputStream(Path.of(correctedRaster))) {
                    corrected = correction.readNBytes((int) size + 1);
                }
                if (corrected.length != size) return null;
            }
            var origins = new SelectedMeasureOrigins();
            var geometry = NativeDecoderStages.geometry(
                    labels, corrected == null ? gray : corrected, width, height, origins);
            return new PreparedGeometry(labels, gray, corrected, width, height, origins, geometry);
        } catch (IOException | RuntimeException refused) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        try (var in = new DataInputStream(new BufferedInputStream(System.in))) {
            PreparedGeometry pending = null;
            for (int count; (count = in.read()) != -1;) {
                if (count != 2 && (count < 5 || count > 11 || (count & 1) == 0))
                    throw new IOException("Invalid document page arguments");
                String[] page = new String[count];
                for (int i = 0; i < count; i++) {
                    int length = in.readInt();
                    if (length < 0 || length > 1024 * 1024)
                        throw new IOException("Invalid document page argument length");
                    byte[] value = new byte[length];
                    in.readFully(value);
                    page[i] = new String(value, StandardCharsets.UTF_8);
                }
                if (count == 2) {
                    pending = prepareGeometry(page[0], page[1]);
                    continue;
                }
                try {
                    if (pending != null) PREPARED.set(pending);
                    Main.main(page);
                } finally {
                    PREPARED.remove();
                    pending = null;
                }
                System.out.write(1);
                System.out.flush();
            }
        }
    }
}
