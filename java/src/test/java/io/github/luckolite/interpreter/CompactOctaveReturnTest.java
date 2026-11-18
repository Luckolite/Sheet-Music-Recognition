// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class CompactOctaveReturnTest {
    private static final int WIDTH = 250;
    private static final int HEIGHT = 150;
    private static final int FIRST_DASH = 59;
    private static final int TERMINAL = 143;
    private static final float GAP = 16;

    @Test
    public void recognizesTheDetachedDownwardTipOfTheShortOctaveLine() throws Exception {
        var ink = glyphAndDots();
        paint(ink, TERMINAL, TERMINAL + 3, 58, 63, 0);
        assertTrue(end(ink) > TERMINAL);
    }

    @Test
    public void requiresTheClosedNumeralDottedLineAndDownwardTipTogether() throws Exception {
        var bare = glyphAndDots();
        assertEquals(-1f, end(bare), 0f);
        var upwards = bare.clone();
        paint(upwards, TERMINAL, TERMINAL + 3, 41, 46, 0);
        assertEquals(-1f, end(upwards), 0f);
        var broken = bare.clone();
        paint(broken, 28, 30, 46, 50, 255);
        paint(broken, TERMINAL, TERMINAL + 3, 58, 63, 0);
        assertEquals(-1f, end(broken), 0f);
        var disconnected = bare.clone();
        paint(disconnected, 87, 115, 48, 55, 255);
        paint(disconnected, TERMINAL, TERMINAL + 3, 58, 63, 0);
        assertEquals(-1f, end(disconnected), 0f);
    }

    private static byte[] glyphAndDots() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 28, 54, 43, 45, 0);
        paint(ink, 28, 30, 43, 72, 0);
        paint(ink, 52, 54, 43, 72, 0);
        paint(ink, 28, 54, 55, 57, 0);
        paint(ink, 28, 54, 70, 72, 0);
        for (int x = FIRST_DASH; x < TERMINAL; x += 9) paint(ink, x, x + 4, 49, 52, 0);
        paint(ink, TERMINAL, TERMINAL + 3, 49, 53, 0);
        return ink;
    }

    private static float end(byte[] page) throws Exception {
        var shape = Class.forName(OctaveMarkDetector.class.getName() + "$InkBox");
        var constructor =
                shape.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var glyph = constructor.newInstance(28, 43, 54, 72, 0);
        var method =
                OctaveMarkDetector.class.getDeclaredMethod(
                        "compactDottedReturn",
                        byte[].class,
                        int.class,
                        int.class,
                        shape,
                        float.class);
        method.setAccessible(true);
        return (float) method.invoke(null, page, WIDTH, HEIGHT, glyph, GAP);
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
