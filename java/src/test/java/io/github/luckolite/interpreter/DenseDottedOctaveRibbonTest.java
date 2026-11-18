// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import org.junit.Test;

public class DenseDottedOctaveRibbonTest {
    private static final int WIDTH = 450;
    private static final int HEIGHT = 150;
    private static final float GAP = 16;

    @Test
    public void oneJoinedDotPairStillRequiresAPrintedDownwardReturn() throws Exception {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 48, 72, 39, 41, 0);
        paint(ink, 48, 50, 39, 65, 0);
        paint(ink, 69, 72, 39, 65, 0);
        paint(ink, 48, 72, 51, 53, 0);
        paint(ink, 48, 72, 63, 65, 0);
        for (int x = 80; x <= 376; x += 8) paint(ink, x, x + 4, 47, 50, 0);
        paint(ink, 133, 135, 47, 50, 0);

        var open = ink.clone();
        paint(ink, 380, 382, 53, 58, 0);
        paint(ink, 380, 382, 61, 65, 0);
        assertNotNull(terminal(ink));
        assertNull(terminal(open));

        var broken = ink.clone();
        paint(broken, 223, 256, 46, 51, 255);
        assertNull(terminal(broken));

        var secondJoinedPair = ink.clone();
        paint(secondJoinedPair, 173, 175, 47, 50, 0);
        assertNull(terminal(secondJoinedPair));
    }

    private static Object terminal(byte[] page) throws Exception {
        var shape = Class.forName(OctaveMarkDetector.class.getName() + "$InkBox");
        var constructor = shape.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var glyph = constructor.newInstance(48, 39, 72, 65, 0);
        var method = OctaveMarkDetector.class.getDeclaredMethod(
                "longClosedDottedRibbon", byte[].class, int.class, int.class, shape, float.class);
        method.setAccessible(true);
        return method.invoke(null, page, WIDTH, HEIGHT, glyph, GAP);
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom, int shade) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) shade);
    }
}
