// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.awt.Font;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generate Bravura meter masks with the decoder's ordinary rasterizer on a qualified JDK. */
public final class GenerateMeterGlyphAtlas {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected the Bravura font and output paths");
        }
        if (!MeterGlyphAtlas.supportsRuntime(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                System.getProperty("java.vendor", ""),
                System.getProperty("java.runtime.version", ""))) {
            throw new IllegalStateException("Use a JDK qualified for the Bravura meter masks");
        }
        var font = Font.createFont(Font.TRUETYPE_FONT, new File(args[0]));
        var rasterizer = MeterFontMatcher.fontRasterizer(font);
        try (var stream =
                new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(Path.of(args[1]))))) {
            for (int size = MeterGlyphAtlas.MIN_SIZE; size <= MeterGlyphAtlas.MAX_SIZE; size++) {
                for (int slot = 0; slot < MeterGlyphAtlas.VALUES_PER_SIZE; slot++) {
                    int number = slot == 16 ? 32 : slot + 1;
                    String glyphs =
                            number < 10
                                    ? Character.toString(0xe080 + number)
                                    : new String(
                                            new char[] {
                                                (char) (0xe080 + number / 10),
                                                (char) (0xe080 + number % 10)
                                            });
                    var mask = rasterizer.render(glyphs, size);
                    stream.writeShort(mask.width());
                    stream.writeShort(mask.height());
                    boolean[] ink = mask.ink();
                    for (int offset = 0; offset < ink.length; offset += Byte.SIZE) {
                        int packed = 0;
                        for (int bit = 0; bit < Byte.SIZE && offset + bit < ink.length; bit++) {
                            if (ink[offset + bit]) packed |= 1 << bit;
                        }
                        stream.writeByte(packed);
                    }
                }
            }
        }
    }
}
