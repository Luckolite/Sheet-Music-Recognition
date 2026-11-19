// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Pre-rendered Bravura meter masks for the Windows rasterizers verified against the font. */
final class MeterGlyphAtlas {
    static final int MIN_SIZE = 7;
    static final int MAX_SIZE = 80;
    static final int VALUES_PER_SIZE = 17;
    private static final int ENTRY_COUNT = (MAX_SIZE - MIN_SIZE + 1) * VALUES_PER_SIZE;
    private static final String TABLE = "glyphs/meter_templates/bravura.bin";
    private static final int TABLE_BYTES = 107721;
    private static final String TABLE_SHA =
            "06382a5027c507880eb241c5a58718313e153abd1ee204c3ade9b85da46feafc";
    private static final int FONT_BYTES = 889228;
    private static final String FONT_SHA =
            "cdf0f893ee1fdb64b7f6713d71ee0dcfc349c0ac01429a8e451b01a9e79f5f3b";

    private final byte[] data;
    private final int[] offsets = new int[ENTRY_COUNT];
    private final AtomicReferenceArray<MeterGlyphMatcher.Glyph> decoded =
            new AtomicReferenceArray<>(ENTRY_COUNT);

    private MeterGlyphAtlas(byte[] data) {
        this.data = data;
        for (int i = 0, at = 0; i < offsets.length; i++) {
            offsets[i] = at;
            at += 4 + ((u16(at) * u16(at + 2) + 7) >>> 3);
        }
    }

    static boolean supportsRuntime(String os, String arch, String vendor, String version) {
        if (!os.startsWith("Windows") || !"amd64".equals(arch)) return false;
        return switch (version) {
            case "17.0.20.1+1" -> "Eclipse Adoptium".equals(vendor);
            case "19.0.1+10-21" -> "Oracle Corporation".equals(vendor);
            case "21.0.10+-14961533-b1163.108" -> "JetBrains s.r.o.".equals(vendor);
            default -> false;
        };
    }

    static MeterGlyphAtlas load() {
        if (!supportsRuntime(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                System.getProperty("java.vendor", ""),
                System.getProperty("java.runtime.version", ""))) return null;
        try (var table = MeterGlyphAtlas.class.getResourceAsStream(TABLE);
                var font = MeterGlyphAtlas.class.getResourceAsStream("Bravura.otf")) {
            if (table == null || font == null) return null;
            byte[] data = table.readNBytes(TABLE_BYTES + 1);
            if (data.length != TABLE_BYTES || !TABLE_SHA.equals(sha256(data))) return null;
            if (!FONT_SHA.equals(sha256(font.readNBytes(FONT_BYTES + 1)))) return null;
            return new MeterGlyphAtlas(data);
        } catch (IOException failure) {
            return null;
        }
    }

    MeterGlyphMatcher.Glyph render(String glyphs, int size) {
        if (size < MIN_SIZE
                || size > MAX_SIZE
                || glyphs.isEmpty()
                || glyphs.length() > 2
                || glyphs.charAt(0) == 0xe080) return null;
        int number = 0;
        for (int i = 0; i < glyphs.length(); i++) {
            int digit = glyphs.charAt(i) - 0xe080;
            if (digit < 0 || digit > 9) return null;
            number = number * 10 + digit;
        }
        if (number < 1 || (number > 16 && number != 32)) return null;
        int slot = number == 32 ? 16 : number - 1;
        int index = (size - MIN_SIZE) * VALUES_PER_SIZE + slot;
        var glyph = decoded.get(index);
        if (glyph != null) return glyph;

        int at = offsets[index];
        int width = u16(at), height = u16(at + 2);
        boolean[] ink = new boolean[width * height];
        for (int i = 0; i < ink.length; i++)
            ink[i] = (data[at + 4 + (i >>> 3)] & (1 << (i & 7))) != 0;
        glyph = new MeterGlyphMatcher.Glyph(width, height, ink);
        decoded.set(index, glyph);
        return glyph;
    }

    private int u16(int i) {
        return ((data[i] & 255) << 8) | (data[i + 1] & 255);
    }

    private static String sha256(byte[] data) {
        try {
            return RasterEvidenceIdentity.hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
