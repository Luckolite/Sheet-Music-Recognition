// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Test;

public class OpeningPortatoSixteenthsTest {
    private static final int WIDTH = 120;
    private static final int HEIGHT = 150;

    @Test
    public void sameColumnsThroughOnePrintingGapReachTheUpperRail() throws ReflectiveOperationException {
        var ink = paper();
        shaft(ink, 54, 55, 35, 83);
        shaft(ink, 54, 55, 94, 102);
        var recovered = candidate(ink);
        assertNotNull(recovered);
        assertEquals(-1, recovered.direction());
        assertEquals(35, recovered.endY());
    }

    @Test
    public void aLaterallyDisplacedFleckCannotJoinToTheHead() throws ReflectiveOperationException {
        var ink = paper();
        shaft(ink, 52, 53, 35, 83);
        shaft(ink, 54, 55, 94, 102);
        assertNull(candidate(ink));
    }

    @Test
    public void aWiderBreakCannotJoinToTheHead() throws ReflectiveOperationException {
        var ink = paper();
        shaft(ink, 54, 55, 35, 80);
        shaft(ink, 54, 55, 94, 102);
        assertNull(candidate(ink));
    }

    private static PrintedBeamShafts.Shaft candidate(byte[] ink) throws ReflectiveOperationException {
        Method method = OpeningPortatoSixteenths.class.getDeclaredMethod("interruptedOpeningShaft",
                byte[].class, int.class, int.class, float.class, float.class, float.class);
        method.setAccessible(true);
        return (PrintedBeamShafts.Shaft) method.invoke(null, ink, WIDTH, HEIGHT, 45f, 104f, 16f);
    }

    private static byte[] paper() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte)255);
        return ink;
    }

    private static void shaft(byte[] ink, int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte)12);
    }
}
