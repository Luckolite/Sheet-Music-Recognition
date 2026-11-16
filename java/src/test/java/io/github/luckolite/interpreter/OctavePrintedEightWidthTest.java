// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

public class OctavePrintedEightWidthTest {
    private static final int WIDTH = 64;

    @Test
    public void includesTheNearestColumnInTheTwoCounterBody() throws Exception {
        byte[] ink = new byte[WIDTH * WIDTH];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 10, 35, 20, 46, 0);
        paint(ink, 16, 30, 24, 30, 255);
        paint(ink, 14, 29, 35, 42, 255);
        paint(ink, 35, 39, 23, 26, 0);

        var detector = OctaveMarkDetector.class;
        Class<?> bounds = Class.forName(detector.getName() + "$InkBox");
        Constructor<?> box =
                bounds.getDeclaredConstructor(
                        int.class, int.class, int.class, int.class, int.class);
        box.setAccessible(true);
        var shape =
                detector.getDeclaredMethod(
                        "bareEightShape", byte[].class, int.class, bounds, float.class);
        shape.setAccessible(true);
        Object attachedDash = box.newInstance(10, 20, 39, 46, 496);

        assertTrue((boolean) shape.invoke(null, ink, WIDTH, attachedDash, 16f));

        byte[] block = ink.clone();
        paint(block, 36, 39, 20, 36, 0);
        assertFalse((boolean) shape.invoke(null, block, WIDTH, attachedDash, 16f));

        byte[] filledCounter = ink.clone();
        paint(filledCounter, 14, 29, 35, 42, 0);
        assertFalse((boolean) shape.invoke(null, filledCounter, WIDTH, attachedDash, 16f));
    }

    private static void paint(byte[] raster, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(raster, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
