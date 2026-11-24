// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class LowerHarmonicStaffTest {
    private static final int WIDTH = 222, HEIGHT = 260, GAP = 18;
    private static final int X = 105, STOP = 152, TOUCH = STOP - 27;
    private static final int BOTTOM = STOP - 33;

    @Test
    public void fullLedgerStopUnderADiamondNeedsTheSamePrintedUpstem() throws Exception {
        byte[] gray = new byte[WIDTH * HEIGHT], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int dx = -14; dx <= 14; dx++) {
            ink(gray, labels, X + dx, TOUCH + 3, 5);
            ink(gray, labels, X + dx, TOUCH + 4, 5);
        }
        int body = 0;
        for (int dy = -10; dy <= 10; dy++) for (int dx = -11; dx <= 11; dx++) {
            int radius = Math.abs(dx) + Math.abs(dy);
            if (radius >= 7 && radius <= 11) ink(gray, labels, X + dx, TOUCH + dy, 5);
            if (dx * dx / 121f + dy * dy / 81f <= 1) {
                ink(gray, labels, X + dx, STOP + dy, 2);
                body++;
            }
        }
        for (int y = STOP - 82; y <= STOP; y++) for (int x = X + 9; x <= X + 11; x++)
            ink(gray, labels, x, y, 1);

        Class<?> interpreter = OmrScoreInterpreter.class;
        Class<?> component = Class.forName(interpreter.getName() + "$Component");
        Class<?> staff = Class.forName(interpreter.getName() + "$Staff");
        Constructor<?> rect = component.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        Constructor<?> staffAt = staff.getDeclaredConstructor(float.class, float.class, float.class);
        rect.setAccessible(true);
        staffAt.setAccessible(true);
        var stopped = rect.newInstance(body, X - 11, X + 11, STOP - 9, STOP + 9, (float) X, (float) STOP);
        var touch = rect.newInstance(20, X - 2, X + 2, TOUCH - 2, TOUCH + 2, (float) X, (float) TOUCH);
        var previous = staffAt.newInstance((float) BOTTOM - GAP * 4, (float) BOTTOM, (float) GAP);
        var following = staffAt.newInstance(217f, 289f, (float) GAP);
        Method owns = interpreter.getDeclaredMethod("lowerHarmonicOnSharedUpstem",
                byte[].class, byte[].class, byte[].class, int.class, int.class, component, staff, List.class);
        owns.setAccessible(true);

        var heads = List.of(stopped, touch);
        assertTrue((boolean) owns.invoke(null, labels, gray, gray, WIDTH, HEIGHT, stopped, previous, heads));
        assertFalse((boolean) owns.invoke(null, labels, gray, gray, WIDTH, HEIGHT, stopped, following, heads));
        assertFalse((boolean) owns.invoke(null, labels, gray, gray, WIDTH, HEIGHT, stopped, previous, List.of(stopped)));
        byte[] cut = gray.clone();
        for (int y = TOUCH + 7; y <= TOUCH + 13; y++)
            Arrays.fill(cut, y * WIDTH + X + 8, y * WIDTH + X + 13, (byte) 255);
        assertFalse((boolean) owns.invoke(null, labels, gray, cut, WIDTH, HEIGHT, stopped, previous, heads));
    }

    private static void ink(byte[] gray, byte[] labels, int x, int y, int label) {
        gray[y * WIDTH + x] = 0;
        labels[y * WIDTH + x] = (byte) label;
    }
}
