// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import org.junit.Test;

public class ClosedStackedOctaveRibbonTest {
    private static final int WIDTH = 870;
    private static final int HEIGHT = 220;
    private static final float GAP = 16;

    @Test
    public void attachedFingeringRequiresTwoCountersAndAClosedRibbon() throws Exception {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 59, 82, 40, 42, 0);
        paint(ink, 59, 61, 40, 68, 0);
        paint(ink, 80, 82, 40, 68, 0);
        paint(ink, 59, 82, 52, 55, 0);
        paint(ink, 59, 82, 65, 68, 0);
        paint(ink, 71, 76, 68, 100, 0);
        for (int x = 86; x <= 743; x += 9) paint(ink, x, x + 4, 50, 52, 0);
        var missingEnd = ink.clone();
        for (int y : new int[] {55, 63, 71, 80}) paint(ink, 745, 747, y, y + 5, 0);
        paint(ink, 746, 746, 69, 70, 0);
        assertNotNull(stacked(ink));
        assertNull(stacked(missingEnd));

        var missingCounter = ink.clone();
        paint(missingCounter, 59, 61, 45, 48, 255);
        assertNull(stacked(missingCounter));
    }

    private static Object stacked(byte[] ink) throws Exception {
        var type = Class.forName(OctaveMarkDetector.class.getName() + "$InkBox");
        var constructor = type.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var numeral = constructor.newInstance(59, 40, 82, 100, 0);
        var staff = new PlayingTechniqueDetector.Staff(108, 172, GAP, 0, 1);
        var prove = OctaveMarkDetector.class.getDeclaredMethod("closedStackedRibbonEight", byte[].class,
                int.class, int.class, type, PlayingTechniqueDetector.Staff.class);
        prove.setAccessible(true);
        return prove.invoke(null, ink, WIDTH, HEIGHT, numeral, staff);
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
