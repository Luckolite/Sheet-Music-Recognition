// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class AttachedShortOctaveRibbonTest {
    private static final int WIDTH = 480;
    private static final int HEIGHT = 150;
    private static final float GAP = 16;

    @Test
    public void touchingNumeralNeedsProofOfTheDetachedCornerAndTwoLowerDashes() throws Exception {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 58, 83, 40, 42, 0);
        paint(ink, 58, 61, 40, 68, 0);
        paint(ink, 79, 83, 40, 68, 0);
        paint(ink, 58, 83, 52, 55, 0);
        paint(ink, 58, 83, 65, 68, 0);
        paint(ink, 45, 60, 60, 68, 0);
        for (int x = 86; x <= 320; x += 9) paint(ink, x, x + 4, 49, 51, 0);
        var open = ink.clone();
        paint(ink, 325, 326, 54, 54, 0);
        paint(ink, 325, 326, 60, 65, 0);
        paint(ink, 325, 326, 70, 74, 0);
        assertTrue(end(ink) > 320);
        assertTrue(end(open) < 0);

        var missingCorner = ink.clone();
        paint(missingCorner, 325, 326, 54, 54, 255);
        assertTrue(end(missingCorner) < 0);
        var missingCounter = ink.clone();
        paint(missingCounter, 58, 61, 46, 49, 255);
        assertTrue(end(missingCounter) < 0);
    }

    private static float end(byte[] ink) throws Exception {
        var type = Class.forName(OctaveMarkDetector.class.getName() + "$InkBox");
        var constructor = type.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var numeral = constructor.newInstance(58, 40, 83, 68, 0);
        var prove = OctaveMarkDetector.class.getDeclaredMethod("attachedShortRibbonEnd", byte[].class,
                int.class, int.class, type, float.class);
        prove.setAccessible(true);
        return (float) prove.invoke(null, ink, WIDTH, HEIGHT, numeral, GAP);
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
