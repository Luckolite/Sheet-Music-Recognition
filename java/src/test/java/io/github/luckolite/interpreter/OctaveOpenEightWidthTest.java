// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class OctaveOpenEightWidthTest {
    private static final int WIDTH = 64;

    @Test
    public void roundsOpenCounterWidthAndStillRequiresAnExistingCounter() throws Exception {
        byte[] ink = new byte[WIDTH * WIDTH];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 10, 39, 20, 46, 0);
        paint(ink, 16, 39, 24, 30, 255);
        paint(ink, 14, 29, 35, 42, 255);

        var detector = OctaveMarkDetector.class;
        Class<?> bounds = Class.forName(detector.getName() + "$InkBox");
        var box =
                bounds.getDeclaredConstructor(
                        int.class, int.class, int.class, int.class, int.class);
        box.setAccessible(true);
        var canRepair =
                detector.getDeclaredMethod(
                        "canRepairEight", byte[].class, int.class, bounds, float.class);
        canRepair.setAccessible(true);
        Object nearestPixel = box.newInstance(10, 20, 39, 46, 500);
        assertTrue((boolean) canRepair.invoke(null, ink, WIDTH, nearestPixel, 16f));

        byte[] extraColumn = ink.clone();
        paint(extraColumn, 40, 40, 20, 46, 0);
        Object tooWide = box.newInstance(10, 20, 40, 46, 527);
        assertFalse((boolean) canRepair.invoke(null, extraColumn, WIDTH, tooWide, 16f));
        byte[] withoutCounter = ink.clone();
        paint(withoutCounter, 14, 29, 35, 42, 0);
        assertFalse((boolean) canRepair.invoke(null, withoutCounter, WIDTH, nearestPixel, 16f));
    }

    private static void paint(byte[] raster, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(raster, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
