// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

public class PairedOctaveRailMaskTest {
    private static final int WIDTH = 144;
    private static final int HEIGHT = 180;
    private static final float GAP = 16;

    @Test
    public void onlyAnUnownedMaskInsideTheCompleteOctaveRailPairIsRejected() throws Exception {
        byte[] labels = new byte[WIDTH * HEIGHT];
        byte[] gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);

        Class<?> head = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var component =
                head.getDeclaredConstructor(
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        float.class,
                        float.class);
        component.setAccessible(true);
        Class<?> staffType = Class.forName(OmrScoreInterpreter.class.getName() + "$Staff");
        var staffConstructor =
                staffType.getDeclaredConstructor(float.class, float.class, float.class);
        staffConstructor.setAccessible(true);
        var staff = staffConstructor.newInstance(95f, 159f, GAP);
        var rejects =
                OmrScoreInterpreter.class.getDeclaredMethod(
                        "pairedOctaveRailMask",
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        head,
                        List.class,
                        staffType);
        rejects.setAccessible(true);

        var left = filledHead(gray, component, 51, 94);
        var right = filledHead(gray, component, 96, 95);
        var leftOctave = filledHead(gray, component, 51, 38);
        var rightOctave = filledHead(gray, component, 96, 39);
        rectangle(gray, 41, 38, 43, 148, (byte) 0);
        rectangle(gray, 86, 39, 88, 153, (byte) 0);
        int minY = HEIGHT, maxY = -1, sumY = 0;
        for (int x = 42; x <= 88; x++) {
            int rail = 144 + Math.round((x - 42) * .12f);
            rectangle(gray, x, rail - 4, x, rail + 4, (byte) 0);
            rectangle(gray, x, rail - 17, x, rail - 9, (byte) 0);
            if (x < 60 || x > 78) continue;
            minY = Math.min(minY, rail - 4);
            maxY = Math.max(maxY, rail + 2);
            for (int y = rail - 4; y <= rail + 2; y++) {
                labels[y * WIDTH + x] = 2;
                sumY += y;
            }
        }
        int members = 19 * 7;
        var mask = component.newInstance(members, 60, 78, minY, maxY, 69f, sumY / (float) members);
        var heads = List.of(left, right, leftOctave, rightOctave, mask);

        assertTrue((boolean) rejects.invoke(null, labels, gray, WIDTH, HEIGHT, mask, heads, staff));
        assertFalse(
                (boolean) rejects.invoke(null, labels, gray, WIDTH, HEIGHT, left, heads, staff));
        assertFalse(
                (boolean) rejects.invoke(null, labels, gray, WIDTH, HEIGHT, right, heads, staff));
        var absentOctave = List.of(left, right, leftOctave, mask);
        assertFalse(
                (boolean)
                        rejects.invoke(
                                null, labels, gray, WIDTH, HEIGHT, mask, absentOctave, staff));

        byte[] erasedRail = gray.clone();
        rectangle(erasedRail, 47, 130, 51, 150, (byte) 255);
        assertFalse(
                (boolean)
                        rejects.invoke(
                                null, labels, erasedRail, WIDTH, HEIGHT, mask, heads, staff));

        byte[] ownShaft = gray.clone();
        rectangle(ownShaft, 77, 107, 79, 146, (byte) 0);
        assertFalse(
                (boolean)
                        rejects.invoke(null, labels, ownShaft, WIDTH, HEIGHT, mask, heads, staff));
    }

    private static Object filledHead(byte[] gray, Constructor<?> component, int x, int y)
            throws ReflectiveOperationException {
        int area = 0;
        for (int dx = -10; dx <= 10; dx++) {
            for (int dy = -9; dy <= 9; dy++) {
                if (dx * dx * 81 + dy * dy * 100 > 8100) continue;
                gray[(y + dy) * WIDTH + x + dx] = 0;
                area++;
            }
        }
        return component.newInstance(area, x - 10, x + 10, y - 9, y + 9, (float) x, (float) y);
    }

    private static void rectangle(
            byte[] gray, int left, int top, int right, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, value);
    }
}
