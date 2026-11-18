// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class OwnedClosingDyadRailMaskTest {
    private static final int WIDTH = 176, HEIGHT = 240, SHAFT = 81;
    private static final float GAP = 16f;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final byte[] labels = new byte[WIDTH * HEIGHT];

    public OwnedClosingDyadRailMaskTest() {
        Arrays.fill(gray, (byte) 255);
        ink(SHAFT - 1, SHAFT + 1, 64, 208, 0);
        head(81, 103, 57, 72);
        head(81, 103, 100, 115);
    }

    @Test
    public void compactHookMustShareTheSameUnbrokenDownwardShaft() throws Exception {
        ink(66, SHAFT + 1, 178, 188, 0);
        label(68, SHAFT, 180, 186);
        assertEquals(1, rejected().size());

        ink(SHAFT - 2, SHAFT + 2, 139, 164, 255);
        assertTrue(rejected().isEmpty());
    }

    @Test
    public void stemlessMaskRequiresTheRawRailOnBothSides() throws Exception {
        for (int x = 56; x <= 129; x++) {
            int center = 188 + Math.round((x - 94) * .12f);
            ink(x, x, center - 4, center + 4, 0);
        }
        for (int x = SHAFT - 1; x <= 106; x++) {
            int center = 188 + Math.round((x - 94) * .12f);
            label(x, x, center - 5, center + 5);
        }
        assertEquals(1, rejected().size());

        ink(108, 123, 179, 201, 255);
        assertTrue(rejected().isEmpty());
    }

    private void head(int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++) {
            int margin = y >= top + 2 && y <= bottom - 2 ? 0 : 3;
            ink(left + margin, right - margin, y, y, 0);
            label(left + margin, right - margin, y, y);
        }
    }

    private void ink(int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }

    private void label(int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(
                    labels,
                    y * WIDTH + left,
                    y * WIDTH + right + 1,
                    OmrMeasurePostProcessor.NOTEHEAD);
    }

    private List<?> rejected() throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var comp =
                interpreter.getDeclaredMethod(
                        "findComponents", byte[].class, int.class, int.class, byte.class);
        comp.setAccessible(true);
        var heads = comp.invoke(null, labels, WIDTH, HEIGHT, OmrMeasurePostProcessor.NOTEHEAD);
        var frame = Class.forName(interpreter.getName() + "$Staff");
        var constructor = frame.getDeclaredConstructor(float.class, float.class, float.class);
        constructor.setAccessible(true);
        var filter =
                interpreter.getDeclaredMethod(
                        "ownedDyadRailMasks",
                        byte[].class,
                        int.class,
                        int.class,
                        List.class,
                        List.class);
        filter.setAccessible(true);
        return (List<?>)
                filter.invoke(
                        null,
                        gray,
                        WIDTH,
                        HEIGHT,
                        heads,
                        List.of(constructor.newInstance(142f, 206f, GAP)));
    }
}
