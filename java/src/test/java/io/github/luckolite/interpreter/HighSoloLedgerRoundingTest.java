// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class HighSoloLedgerRoundingTest {
    private static final int WIDTH = 240;
    private static final int HEIGHT = 380;
    private static final int GAP = 16;
    private static final int LOWER_TOP = 270;

    @Test
    public void subpixelStaffLimitRequiresPrintedRulesInBothPlanes() throws Exception {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 153; y <= 170; y++) {
            double f = (y - 161.5) / 9.0;
            int span = (int) Math.floor(11 * Math.sqrt(1 - f * f));
            ink(gray, 120 - span, y, 120 + span, y, 0);
        }
        ink(gray, 110, 161, 112, 304, 0);
        for (int n = 1; n <= 5; n++) {
            int y = LOWER_TOP - n * GAP;
            ink(gray, 105, y - 1, 135, y + 1, 0);
        }
        assertTrue(admitted(gray, gray, 161.75f));
        assertFalse(admitted(gray, gray, 160.75f));
        byte[] missing = gray.clone();
        ink(missing, 105, 221, 135, 223, 255);
        assertFalse(admitted(gray, missing, 161.75f));
        assertFalse(admitted(missing, gray, 161.75f));
    }

    private static void ink(byte[] gray, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
        }
    }

    private static boolean admitted(byte[] gray, byte[] raw, float y) throws Exception {
        var outer = OmrScoreInterpreter.class;
        var head = Class.forName(outer.getName() + "$Component");
        var staff = Class.forName(outer.getName() + "$Staff");
        var component = head.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        component.setAccessible(true);
        var shape = staff.getDeclaredConstructor(float.class, float.class, float.class);
        shape.setAccessible(true);
        Object oval = component.newInstance(310, 109, 131, 152, 171, 120f, y);
        Object upper = shape.newInstance(16f, 80f, (float) GAP);
        Object lower = shape.newInstance((float) LOWER_TOP, LOWER_TOP + 4f * GAP, (float) GAP);
        var gate = outer.getDeclaredMethod("roundsToHighSoloStaff", byte[].class, byte[].class,
                int.class, int.class, head, staff, staff);
        gate.setAccessible(true);
        return (boolean) gate.invoke(null, gray, raw, WIDTH, HEIGHT, oval, upper, lower);
    }
}
