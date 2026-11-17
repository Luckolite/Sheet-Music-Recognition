// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class RomanStringAccidentalOwnershipTest {
    private static final int WIDTH = 256, HEIGHT = 196;
    private static final float GAP = 16;
    private static final PrintedRomanStrings.Box FRAGMENT =
            new PrintedRomanStrings.Box(111, 74, 121, 97);

    @Test
    public void completeStackedStringMarksOwnTheLaterMisreadShaft() {
        var ink = paper();
        assertTrue(owns(ink));
        var noAdjacentShaft = ink.clone();
        paint(noAdjacentShaft, 122, 132, 74, 96, 255);
        assertFalse(owns(noAdjacentShaft));
        var noUpperMark = ink.clone();
        paint(noUpperMark, 114, 129, 44, 70, 255);
        assertFalse(owns(noUpperMark));
    }

    @Test
    public void aFlatInTheSameApertureDoesNotHaveTwoCappedEnds() {
        var ink = paper();
        paint(ink, 111, 120, 74, 96, 255);
        paint(ink, 111, 113, 74, 96, 0);
        for (int y = 83; y < 96; y++)
            for (int x = 113; x <= 120; x++) {
                int dx = x - 113, dy = y - 89;
                if (dx * dx / 49f + dy * dy / 42f <= 1) ink[y * WIDTH + x] = 0;
            }
        assertFalse(owns(ink));
    }

    private static boolean owns(byte[] ink) {
        return PrintedRomanStrings.ownsAccidentalFragment(
                ink, WIDTH, HEIGHT, FRAGMENT, 120.5, 117.5, GAP);
    }

    private static byte[] paper() {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        serifI(ink, 117, 46);
        serifI(ink, 111, 74);
        serifI(ink, 122, 74);
        return ink;
    }

    private static void serifI(byte[] ink, int x, int y) {
        paint(ink, x + 3, x + 6, y, y + 22, 0);
        paint(ink, x, x + 9, y, y + 2, 0);
        paint(ink, x, x + 9, y + 20, y + 22, 0);
    }

    private static void paint(byte[] ink, int x0, int x1, int y0, int y1, int value) {
        for (int y = y0; y <= y1; y++)
            Arrays.fill(ink, y * WIDTH + x0, y * WIDTH + x1 + 1, (byte) value);
    }
}
