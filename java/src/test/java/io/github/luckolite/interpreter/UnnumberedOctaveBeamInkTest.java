// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class UnnumberedOctaveBeamInkTest {
    private static final int WIDTH = 100;
    private static final int HEIGHT = 64;

    @Test
    public void allowsFingeringWhileHoldingAnAmbiguousThree() {
        assertTrue(clear(new String[]{
                "..##.", ".###.", "####.", "..##.",
                "..##.", "..##.", "..##.", "..##.", ".####"}, 2));
        assertTrue(clear(new String[]{
                "..###..", ".##.##.", "##...##", ".....##",
                "...###.", "..##...", ".##....", "#######", "#######"}, 2));
        assertFalse(clear(new String[]{
                "#####", "...##", "...##", "....#", "#####",
                "...##", "...##", "...##", "#####"}, 2));
        assertFalse(clear(new String[]{
                "...#####", "..###..#", "..##...#", "....####",
                "..####..", "#....##.", "#...##..", "######..", ".####..."}, 2));
    }

    private static boolean clear(String[] glyph, int scale) {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int row = 0; row < glyph.length; row++)
            for (int column = 0; column < glyph[row].length(); column++)
                if (glyph[row].charAt(column) == '#')
                    for (int y = 0; y < scale; y++)
                        Arrays.fill(ink, (12 + row * scale + y) * WIDTH + 40 + column * scale,
                                (12 + row * scale + y) * WIDTH + 40 + (column + 1) * scale, (byte) 0);
        return UnnumberedTripletInk.hasOnlyFingeringInk(ink, WIDTH, HEIGHT, 8, 90, 3, 49, 18f, 79f, 16f, null);
    }
}
