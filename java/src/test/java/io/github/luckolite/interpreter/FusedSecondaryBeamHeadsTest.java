// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class FusedSecondaryBeamHeadsTest {
    private static final int WIDTH = 190;
    private static final int HEIGHT = 120;
    private static final float GAP = 16;
    private static final int[] FIRST = {34, 40, -1};
    private static final int[] MIDDLE = {72, 43, -1};
    private static final int[] LAST = {123, 46, -1};

    @Test
    public void twoOwnedRailsExplainTheWholeMaskWithoutAnIndependentHead() throws Exception {
        var raw = new byte[WIDTH * HEIGHT];
        Arrays.fill(raw, (byte) 255);
        for (int x = FIRST[0]; x <= LAST[0]; x++) {
            int top = tip(x);
            paint(raw, x, top, x, top + 8, 0);
            paint(raw, x, top + 12, x, top + 20, 0);
            if (x >= 95) paint(raw, x, top + 8, x, top + 12, 0);
        }
        int[] mask = new int[13 * 7];
        int index = 0;
        for (int x = 97; x <= 109; x++)
            for (int y = tip(x) + 7; y <= tip(x) + 13; y++)
                mask[index++] = y * WIDTH + x;
        assertTrue(proves(raw, mask));

        var erased = raw.clone();
        for (int x = 82; x <= 92; x++) paint(erased, x, tip(x) + 13, x, tip(x) + 19, 255);
        assertFalse(proves(erased, mask));

        var solid = raw.clone();
        for (int x = FIRST[0]; x <= MIDDLE[0]; x++) paint(solid, x, tip(x) + 8, x, tip(x) + 12, 0);
        assertFalse(proves(solid, mask));

        var independentInk = Arrays.copyOf(mask, mask.length + 1);
        int x = 103;
        paint(raw, x - 1, tip(x) + 22, x + 1, tip(x) + 26, 0);
        independentInk[mask.length] = (tip(x) + 24) * WIDTH + x;
        assertFalse(proves(raw, independentInk));
    }

    private static boolean proves(byte[] gray, int[] mask) throws Exception {
        var test = OmrScoreInterpreter.class.getDeclaredMethod("fusedSecondaryBeamOwns",
                byte[].class, int.class, int.class, float.class, int[].class,
                int[].class, int[].class, int[].class);
        test.setAccessible(true);
        return (boolean) test.invoke(null, gray, WIDTH, HEIGHT, GAP, mask, FIRST, MIDDLE, LAST);
    }

    private static int tip(int x) {
        return Math.round(FIRST[1] + (LAST[1] - FIRST[1])
                * (x - FIRST[0]) / (float) (LAST[0] - FIRST[0]));
    }

    private static void paint(byte[] raw, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(raw, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
