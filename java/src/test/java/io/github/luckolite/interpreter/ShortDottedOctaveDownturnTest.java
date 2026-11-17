// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShortDottedOctaveDownturnTest {
    private static final int WIDTH = 300, HEIGHT = 120;

    @Test
    public void shortCornerNeedsBothTheHorizontalDashesAndDetachedVerticalDot() throws Exception {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 221, 225, 42, 44, 0);
        paint(ink, 229, 232, 42, 44, 0);
        paint(ink, 230, 232, 42, 52, 0);
        paint(ink, 229, 233, 56, 60, 0);
        assertTrue(returnWitness(ink));

        var joined = ink.clone();
        paint(joined, 230, 232, 53, 55, 0);
        assertFalse(returnWitness(joined));

        var detachedFromRibbon = ink.clone();
        paint(detachedFromRibbon, 221, 225, 42, 44, 255);
        assertFalse(returnWitness(detachedFromRibbon));

        paint(ink, 229, 233, 56, 60, 255);
        assertFalse(returnWitness(ink));
    }

    private static boolean returnWitness(byte[] ink) throws Exception {
        var bounds = Class.forName(OctaveMarkDetector.class.getName() + "$InkBox");
        var init = bounds.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        var method = OctaveMarkDetector.class.getDeclaredMethod(
                "shortDottedDownturn", byte[].class, int.class, int.class, bounds, float.class, float.class);
        init.setAccessible(true);
        method.setAccessible(true);
        return (boolean) method.invoke(null, ink, WIDTH, HEIGHT, init.newInstance(32, 36, 61, 62, 0), 241f, 16f);
    }

    private static void paint(byte[] ink, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
