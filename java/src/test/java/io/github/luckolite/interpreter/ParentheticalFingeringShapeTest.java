// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.BitSet;
import org.junit.Test;

public class ParentheticalFingeringShapeTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 72;

    @Test
    public void openingAndClosingCurvesRetainOppositeOrientations() throws ReflectiveOperationException {
        assertEquals(1, direction(bracket(true), new BitSet()));
        assertEquals(-1, direction(bracket(false), new BitSet()));
    }

    @Test
    public void aShortenedOpeningRequiresTheMeasuredRuleAtItsTop() throws ReflectiveOperationException {
        var ink = bracket(true);
        for (int row = 15; row <= 21; row++)
            Arrays.fill(ink, row * WIDTH, (row + 1) * WIDTH, (byte)255);
        var observed = new BitSet();
        observed.set(21);
        assertEquals(1, direction(ink, observed));
        assertEquals(0, direction(ink, new BitSet()));
    }

    @Test
    public void aStraightStemCannotSupplyAParenthesis() throws ReflectiveOperationException {
        var ink = paper();
        for (int row = 15; row <= 42; row++)
            Arrays.fill(ink, row * WIDTH + 22, row * WIDTH + 25, (byte)0);
        assertEquals(0, direction(ink, new BitSet()));
    }

    private static byte[] bracket(boolean opening) {
        var ink = paper();
        for (int y = 15; y <= 42; y++) {
            int bend = Math.round(5 * (float)Math.sin(Math.PI * (y - 15) / 27));
            int x = opening ? 26 - bend : 22 + bend;
            Arrays.fill(ink, y * WIDTH + x, y * WIDTH + x + 3, (byte)0);
        }
        return ink;
    }

    private static byte[] paper() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte)255);
        return ink;
    }

    private static int direction(byte[] ink, BitSet rule) throws ReflectiveOperationException {
        var component = RawNumeralComponents.capture(ink, WIDTH, HEIGHT, 0, 0, WIDTH - 1, HEIGHT - 1,
                () -> {}, () -> {}).components().get(0);
        Method method = ParentheticalFingerings.class.getDeclaredMethod("curve",
                RawNumeralComponents.Component.class, int.class, float.class, BitSet.class);
        method.setAccessible(true);
        return (int)method.invoke(null, component, WIDTH, 16f, rule);
    }
}
