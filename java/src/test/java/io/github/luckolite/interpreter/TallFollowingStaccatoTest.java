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

public final class TallFollowingStaccatoTest {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 240;
    private static final float GAP = 16;

    @Test
    public void narrowFollowingStaccatoRequiresBothSharedRails() throws Exception {
        assertTrue(witness(true, true));
        assertFalse(witness(true, false));
    }

    @Test
    public void similarlySizedRoundDotRetainsIndependentOwnership() throws Exception {
        assertFalse(witness(false, true));
    }

    private static boolean witness(boolean narrow, boolean sharedRails) throws Exception {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        int direction = 1;
        int first = orientedY(96, direction);
        int next = orientedY(112, direction);
        oval(gray, 120, first, 10, 8);
        oval(gray, 162, next, 10, 8);
        oval(gray, 139, orientedY(91, direction), 4, 4);
        oval(gray, 160, orientedY(92, direction), narrow ? 2 : 3, 3);
        oval(gray, 120, orientedY(77, direction), 3, 3);
        stem(gray, 120 - 9 * direction, first, orientedY(181, direction));
        stem(gray, 162 - 9 * direction, next, orientedY(183, direction));
        if (sharedRails) {
            for (int x = 111; x <= 153; x++) {
                int tip = 181 + Math.round((x - 111) * 2f / 42);
                for (int dy = -3; dy <= 3; dy++) {
                    gray[(tip - 4 + dy) * WIDTH + x] = 0;
                    gray[(tip - 15 + dy) * WIDTH + x] = 0;
                }
            }
        }

        Class<?> component = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        Constructor<?> box =
                component.getDeclaredConstructor(
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        float.class,
                        float.class);
        box.setAccessible(true);
        Object a = body(box, 120, first, 10, 8, 280);
        Object b = body(box, 162, next, 10, 8, 280);
        Object firstDot = body(box, 139, orientedY(91, direction), 3, 3, 37);
        Object secondDot =
                box.newInstance(33, narrow ? 158 : 157, narrow ? 162 : 163, 89, 95, 159.7f, 92f);
        Method method =
                OmrScoreInterpreter.class.getDeclaredMethod(
                        "widelySpacedStaccatoPair",
                        byte[].class,
                        int.class,
                        int.class,
                        component,
                        component,
                        component,
                        List.class,
                        float.class);
        method.setAccessible(true);
        return (boolean)
                method.invoke(null, gray, WIDTH, HEIGHT, a, firstDot, secondDot, List.of(b), GAP);
    }

    private static Object body(Constructor<?> box, int x, int y, int rx, int ry, int area)
            throws ReflectiveOperationException {
        return box.newInstance(area, x - rx, x + rx, y - ry, y + ry, (float) x, (float) y);
    }

    private static int orientedY(int y, int direction) {
        return direction == 1 ? y : HEIGHT - y;
    }

    private static void stem(byte[] gray, int x, int start, int end) {
        for (int y = Math.min(start, end); y <= Math.max(start, end); y++) {
            gray[y * WIDTH + x] = 0;
            gray[y * WIDTH + x + 1] = 0;
        }
    }

    private static void oval(byte[] gray, int x, int y, int rx, int ry) {
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dy = -ry; dy <= ry; dy++) {
                double sx = (double) dx / rx;
                double sy = (double) dy / ry;
                if (sx * sx + sy * sy <= 1) gray[(y + dy) * WIDTH + x + dx] = 0;
            }
        }
    }
}
