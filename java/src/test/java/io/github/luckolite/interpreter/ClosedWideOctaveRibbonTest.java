// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Test;

public class ClosedWideOctaveRibbonTest {
    private static final int WIDTH = 280;
    private static final int HEIGHT = 134;
    private static final float GAP = 16;

    @Test
    public void wideBareEightRequiresBothCountersAndAClosedPrintedRibbon() throws Exception {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 28, 55, 39, 41, 0);
        paint(ink, 28, 30, 39, 65, 0);
        paint(ink, 49, 52, 39, 65, 0);
        paint(ink, 28, 52, 51, 53, 0);
        paint(ink, 28, 52, 63, 65, 0);
        paint(ink, 54, 55, 40, 47, 0);
        for (int x = 62; x < 220; x += 9) paint(ink, x, x + 4, 44, 46, 0);
        var noHook = ink.clone();
        paint(ink, 217, 219, 45, 57, 0);
        assertTrue(closed(ink));
        assertFalse(closed(noHook));

        var openCounter = ink.clone();
        paint(openCounter, 28, 30, 43, 47, 255);
        assertFalse(closed(openCounter));
        var lostRail = ink.clone();
        paint(lostRail, 105, 187, 44, 46, 255);
        assertFalse(closed(lostRail));
    }

    private static boolean closed(byte[] page) throws Exception {
        var shape = Class.forName(OctaveMarkDetector.class.getName() + "$InkBox");
        var constructor = shape.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var glyph = constructor.newInstance(28, 39, 55, 65, 0);
        Method method = OctaveMarkDetector.class.getDeclaredMethod(
                "closedWideRibbonEight", byte[].class, int.class, int.class, shape, float.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, page, WIDTH, HEIGHT, glyph, GAP);
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
