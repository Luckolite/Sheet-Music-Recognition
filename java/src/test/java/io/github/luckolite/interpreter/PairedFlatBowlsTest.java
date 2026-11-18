// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.Test;

public class PairedFlatBowlsTest {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 220;
    private static final float GAP = 16;

    @Test
    public void aJoinedLabelCanSupplyTwoIndependentFlatBowls() throws Exception {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        drawFlat(ink, 95, 0);
        drawFlat(ink, 139, 4);
        for (int y = 108; y < 112; y++) ink[y * WIDTH + 51 + y - 108] = 0;
        int[] members = IntStream.range(0, ink.length).filter(i -> ink[i] == 0).toArray();

        byte[] original = ink.clone();
        fill(original, 30, 110, 96, 98, 0);
        fill(original, 30, 110, 138, 140, 0);
        assertTrue(proves(original, members, 139));
        assertFalse(proves(original, members, 130));

        byte[] oneBowl = original.clone();
        fill(oneBowl, 56, 70, 128, 148, 255);
        assertFalse(proves(oneBowl, members, 139));
    }

    private static boolean proves(byte[] ink, int[] members, float lowerY) throws Exception {
        Class<?> c = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var component = c.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        component.setAccessible(true);
        Object upper = component.newInstance(250, 68, 88, 87, 103, 78f, 95f);
        Object lower = component.newInstance(250, 69, 89, Math.round(lowerY) - 8,
                Math.round(lowerY) + 8, 79f, lowerY);
        Object glyph = component.newInstance(members.length, 48, 68, 68, 151, 54f, 111f);
        var method = OmrScoreInterpreter.class.getDeclaredMethod("twoFlatShapes", byte[].class,
                int.class, int.class, c, int[].class, c, c, float.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, ink, WIDTH, HEIGHT, glyph, members, upper, lower, GAP);
    }

    private static void drawFlat(byte[] ink, int center, int shift) {
        fill(ink, 48 + shift, 52 + shift, center - 27, center + 13, 0);
        fill(ink, 51 + shift, 63 + shift, center - 8, center - 5, 0);
        fill(ink, 61 + shift, 65 + shift, center - 6, center + 4, 0);
        fill(ink, 57 + shift, 63 + shift, center + 4, center + 7, 0);
        fill(ink, 51 + shift, 59 + shift, center + 7, center + 10, 0);
    }

    private static void fill(byte[] ink, int left, int right, int top, int bottom, int value) {
        for (int y = top; y < bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right, (byte) value);
    }
}
