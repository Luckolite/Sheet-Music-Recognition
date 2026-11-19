// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;

import java.awt.Font;
import org.junit.Test;

public class MeterGlyphAtlasTest {
    @Test
    public void bundledMasksMatchTheOrdinaryRenderer() throws Exception {
        boolean qualified =
                MeterGlyphAtlas.supportsRuntime(
                        System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
                        System.getProperty("java.vendor", ""),
                                System.getProperty("java.runtime.version", ""));
        var atlas = MeterGlyphAtlas.load();
        if (!qualified) {
            assertNull(atlas);
            return;
        }
        assertNotNull(atlas);
        try (var font = getClass().getResourceAsStream("Bravura.otf")) {
            assertNotNull(font);
            var original =
                    MeterFontMatcher.fontRasterizer(Font.createFont(Font.TRUETYPE_FONT, font));
            for (int size = MeterGlyphAtlas.MIN_SIZE; size <= MeterGlyphAtlas.MAX_SIZE; size++) {
                for (int slot = 0; slot < MeterGlyphAtlas.VALUES_PER_SIZE; slot++) {
                    int number = slot == 16 ? 32 : slot + 1;
                    StringBuilder glyph = new StringBuilder();
                    for (char digit : Integer.toString(number).toCharArray())
                        glyph.append((char) (0xe080 + digit - '0'));
                    String text = glyph.toString();
                    var expected = original.render(text, size);
                    var actual = atlas.render(text, size);
                    assertEquals(expected.width(), actual.width());
                    assertEquals(expected.height(), actual.height());
                    assertArrayEquals(expected.ink(), actual.ink());
                }
            }
        }
        assertNull(atlas.render("\ue081", MeterGlyphAtlas.MAX_SIZE + 1));
        assertNull(atlas.render("1", MeterGlyphAtlas.MIN_SIZE));
    }

    @Test
    public void atlasRequiresTheQualifiedPlatformAndRuntimeBuild() {
        assertTrue(
                MeterGlyphAtlas.supportsRuntime(
                        "Windows 11", "amd64", "Eclipse Adoptium", "17.0.20.1+1"));
        assertFalse(
                MeterGlyphAtlas.supportsRuntime(
                        "Windows 11", "amd64", "Eclipse Adoptium", "17.0.20.1+2"));
        assertFalse(
                MeterGlyphAtlas.supportsRuntime(
                        "Linux", "amd64", "Eclipse Adoptium", "17.0.20.1+1"));
        assertFalse(
                MeterGlyphAtlas.supportsRuntime(
                        "Windows 11", "aarch64", "Eclipse Adoptium", "17.0.20.1+1"));
    }
}
