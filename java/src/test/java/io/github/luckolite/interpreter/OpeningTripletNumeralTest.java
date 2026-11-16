// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OpeningTripletNumeralTest {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 190;
    private static final float X = 100;
    private static final float Y = 100;
    private static final float GAP = 16;

    @Test
    public void acceptsOnlyTheOwnedThreeInBothRasterPlanes() {
        var page = new Page();
        assertTrue(page.proved(List.of(ocr("3")), page.original));
        assertFalse(page.proved(List.of(), page.original));
        assertFalse(page.proved(List.of(ocr("8")), page.original));
        assertFalse(page.proved(List.of(ocr("3"), ocr("3")), page.original));

        var erased = page.original.clone();
        for (int y = 114; y < 133; y++)
            Arrays.fill(erased, y * WIDTH + 93, y * WIDTH + 110, (byte) 255);
        assertFalse(page.proved(List.of(ocr("3")), erased));
    }

    private static PlayingTechniqueDetector.Word ocr(String text) {
        return new PlayingTechniqueDetector.Word(text,
                93f / WIDTH, 111f / HEIGHT, 108f / WIDTH, 133f / HEIGHT);
    }

    private static final class Page {
        final byte[] original = new byte[WIDTH * HEIGHT];
        final byte[] labels = new byte[original.length];

        Page() {
            Arrays.fill(original, (byte) 255);
            String[] three = {
                "..#######..",
                ".#########.",
                "###.....###",
                "##......###",
                "........###",
                ".......###.",
                ".....####..",
                "....#####..",
                "......####.",
                "........###",
                "........###",
                "###.....###",
                "###.....###",
                ".#########.",
                "..#######.."
            };
            for (int y = 0; y < three.length; y++) {
                for (int x = 0; x < three[y].length(); x++) {
                    if (three[y].charAt(x) == '.') continue;
                    int pixel = (116 + y) * WIDTH + 95 + x;
                    original[pixel] = 0;
                    labels[pixel] = OmrMeasurePostProcessor.SYMBOL;
                }
            }
        }

        boolean proved(List<PlayingTechniqueDetector.Word> words, byte[] corrected) {
            return OpeningTripletNumeral.present(words, original, corrected, labels,
                    WIDTH, HEIGHT, X, Y, GAP);
        }
    }
}
