// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class PaleOctaveCountersTest {
    private static final int WIDTH = 130;
    private static final int HEIGHT = 120;
    private static final float GAP = 16;

    @Test
    public void boundedPaleEvidenceMustCloseBothCountersInOneContour() throws Exception {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 49, 75, 40, 43, 0);
        paint(ink, 49, 52, 40, 70, 0);
        paint(ink, 72, 75, 40, 70, 0);
        paint(ink, 49, 75, 53, 56, 0);
        paint(ink, 49, 75, 67, 70, 0);
        assertFalse(pale(ink));

        paint(ink, 49, 52, 47, 50, 190);
        assertTrue(pale(ink));
        paint(ink, 72, 75, 60, 62, 244);
        assertTrue(pale(ink));
        paint(ink, 49, 52, 47, 50, 255);
        assertFalse(pale(ink));
    }

    private static boolean pale(byte[] ink) throws Exception {
        var type = Class.forName(OctaveMarkDetector.class.getName() + "$InkBox");
        var constructor = type.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var numeral = constructor.newInstance(49, 40, 75, 70, 0);
        var prove = OctaveMarkDetector.class.getDeclaredMethod("paleCounterShape", byte[].class,
                int.class, int.class, type, float.class);
        prove.setAccessible(true);
        return (boolean) prove.invoke(null, ink, WIDTH, HEIGHT, numeral, GAP);
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
