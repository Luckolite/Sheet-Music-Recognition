// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import org.junit.Test;

public class ClosedSystemOctaveRibbonSeedTest {
    private static final int WIDTH = 870;
    private static final int HEIGHT = 220;
    private static final float GAP = 16;

    @Test
    public void displacedSeedRequiresContinuousLongDotsAndAClosedDownwardTurn() throws Exception {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int x = 86; x <= 743; x += 9) paint(ink, x, x + 4, 50, 52, 0);
        var open = ink.clone();
        for (int y : new int[]{55,63,71,80}) paint(ink, 745, 747, y, y+5, 0);
        paint(ink, 746, 746, 69, 70, 0);
        assertNotNull(terminal(ink));
        assertNull(terminal(open));

        var severed = ink.clone();
        paint(severed, 310, 432, 49, 55, 255);
        assertNull(terminal(severed));
    }

    @Test
    public void displacedOpenLineRequiresTheActualStaffBoundary() throws Exception {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int x = 86; x <= 813; x += 9) paint(ink, x, x + 4, 50, 52, 0);
        assertNull(terminal(ink));
        for (int y = 108; y <= 172; y += 16) paint(ink, 34, 813, y, y, 0);
        paint(ink, 811, 813, 108, 172, 0);
        assertNotNull(terminal(ink));
        paint(ink, 310, 432, 49, 55, 255);
        assertNull(terminal(ink));
    }

    private static Object terminal(byte[] ink) throws Exception {
        var shape = Class.forName(OctaveMarkDetector.class.getName() + "$InkBox");
        var constructor = shape.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var numeral = constructor.newInstance(59, 40, 83, 68, 0);
        var staff = new PlayingTechniqueDetector.Staff(108, 172, GAP, 0, 1);
        var end = OctaveMarkDetector.class.getDeclaredMethod("fullSystemDottedEnd", byte[].class,
                int.class, int.class, shape, PlayingTechniqueDetector.Staff.class);
        end.setAccessible(true);
        return end.invoke(null, ink, WIDTH, HEIGHT, numeral, staff);
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
