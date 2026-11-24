// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class LedgerSpaceOctaveTest {
    private static final int WIDTH = 225;
    private static final int HEIGHT = 240;
    private static final int GAP = 16;

    @Test
    public void usesTheSamePrintedShaftAndDoubleBeamFromEitherSide() throws Exception {
        var c = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var s = Class.forName(OmrScoreInterpreter.class.getName() + "$Staff");
        var component = c.getDeclaredConstructors()[0];
        component.setAccessible(true);
        var staff = s.getDeclaredConstructor(float.class, float.class, float.class);
        staff.setAccessible(true);
        var frame = staff.newInstance(118f, 182f, (float) GAP);
        var owner = staff.newInstance(119f, 183f, (float) GAP);
        var admit = OmrScoreInterpreter.class.getDeclaredMethod("shortLedgerSpaceOctave",
                byte[].class, byte[].class, int.class, int.class, c, s, s, List.class);
        admit.setAccessible(true);
        var first = head(component, 80, 78);
        var second = head(component, 109, 62);
        var lowerFirst = head(component, 80, 134);
        var lowerSecond = head(component, 109, 118);
        var heads = List.of(first, second, lowerFirst, lowerSecond);

        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int row = 118; row <= 182; row += GAP) fill(ink, 30, row, 190, row + 1);
        oval(ink, 80, 78);
        oval(ink, 109, 62);
        oval(ink, 80, 134);
        oval(ink, 109, 118);
        fill(ink, 70, 82, 72, 198);
        fill(ink, 99, 66, 101, 193);
        for (int y : new int[] {86, 102}) fill(ink, 69, y, 91, y + 2);
        for (int y : new int[] {70, 86, 102}) fill(ink, 98, y, 120, y + 2);
        for (int x = 70; x <= 101; x++) {
            int y = Math.round(194 - (x - 70) * 5 / 30f);
            fill(ink, x, y, x, y + 4);
            fill(ink, x, y - 11, x, y - 7);
        }
        assertTrue((boolean) admit.invoke(null, ink, ink.clone(), WIDTH, HEIGHT, first, frame, owner, heads));
        assertTrue((boolean) admit.invoke(null, ink, ink.clone(), WIDTH, HEIGHT, second, frame, owner, heads));
        byte[] cut = ink.clone();
        for (int y = 109; y <= 117; y++) Arrays.fill(cut, y * WIDTH + 68, y * WIDTH + 74, (byte) 255);
        assertFalse((boolean) admit.invoke(null, ink, cut, WIDTH, HEIGHT, first, frame, owner, heads));
        assertFalse((boolean) admit.invoke(null, ink, ink.clone(), WIDTH, HEIGHT, first, frame, owner,
                List.of(first, second, lowerSecond)));
    }

    private static Object head(Constructor<?> c, int x, int y) throws Exception {
        return c.newInstance(270, x - 10, x + 10, y - 8, y + 8, (float) x, (float) y);
    }

    private static void oval(byte[] ink, int cx, int cy) {
        for (int dy = -8; dy <= 8; dy++)
            for (int dx = -11; dx <= 11; dx++)
                if (dx * dx / 121f + dy * dy / 64f <= 1) ink[(cy + dy) * WIDTH + cx + dx] = 0;
    }

    private static void fill(byte[] ink, int x0, int y0, int x1, int y1) {
        for (int y = y0; y <= y1; y++) Arrays.fill(ink, y * WIDTH + x0, y * WIDTH + x1 + 1, (byte) 0);
    }
}
