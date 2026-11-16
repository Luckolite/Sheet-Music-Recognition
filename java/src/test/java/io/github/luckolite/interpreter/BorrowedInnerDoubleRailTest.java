// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public final class BorrowedInnerDoubleRailTest {
    private static final int WIDTH = 220;
    private static final int HEIGHT = 240;
    private static final float GAP = 16;

    @Test
    public void separatedRailsProveTheInternalBridge() throws Exception {
        var field = new Field();
        assertTrue(field.owns(field.gray.clone()));
    }

    @Test
    public void aRealBulbOutsideTheBeamCorridorStays() throws Exception {
        var field = new Field();
        field.addMember(89, field.tip(89) + 4);
        assertFalse(field.owns(field.gray.clone()));
    }

    @Test
    public void anUnbrokenBlockCannotSupplyTwoRailOwnership() throws Exception {
        var field = new Field();
        for (int x = 55; x <= 115; x++) {
            for (int depth = 10; depth <= 14; depth++) field.ink(x, field.tip(x) - depth);
        }
        assertFalse(field.owns(field.gray.clone()));
    }

    @Test
    public void normalizedInkNeedsTheMatchingSourceRail() throws Exception {
        var field = new Field();
        byte[] source = field.gray.clone();
        for (int x = 99; x <= 107; x++) {
            for (int depth = 15; depth <= 21; depth++) {
                source[(field.tip(x) - depth) * WIDTH + x] = (byte) 255;
            }
        }
        assertFalse(field.owns(source));
    }

    private static final class Field {
        final byte[] gray = new byte[WIDTH * HEIGHT];
        final int[] left = {55, 190, 1}, right = {115, 201, 1};
        final float slope = (right[1] - left[1]) / (float) (right[0] - left[0]);
        final ArrayList<Integer> pixels = new ArrayList<>();

        Field() {
            Arrays.fill(gray, (byte) 255);
            for (int x = left[0]; x <= right[0]; x++) {
                for (int depth = 3; depth <= 9; depth++) ink(x, tip(x) - depth);
                for (int depth = 15; depth <= 21; depth++) ink(x, tip(x) - depth);
            }
            for (int x = 78; x <= 94; x++) {
                for (int depth = 4; depth <= 20; depth++) {
                    double dx = (x - 86) / 8., dy = (depth - 12) / 8.;
                    if (dx * dx + dy * dy <= 1) addMember(x, tip(x) - depth);
                }
            }
        }

        int tip(int x) {
            return Math.round(left[1] + (x - left[0]) * slope);
        }

        void ink(int x, int y) {
            gray[y * WIDTH + x] = 0;
        }

        void addMember(int x, int y) {
            ink(x, y);
            pixels.add(y * WIDTH + x);
        }

        boolean owns(byte[] source) throws Exception {
            Class<?> type = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
            Constructor<?> ctor = type.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class, float.class, float.class);
            ctor.setAccessible(true);
            Object mask = ctor.newInstance(pixels.size(), 78, 94, 176, 201, 86f, 185f);
            Method proof = OmrScoreInterpreter.class.getDeclaredMethod(
                    "matchesBorrowedInnerRails",
                    byte[].class, byte[].class, int.class, int.class, type,
                    int[].class, int[].class, int[].class, float.class, float.class);
            proof.setAccessible(true);
            return (boolean) proof.invoke(null, gray, source, WIDTH, HEIGHT, mask,
                    pixels.stream().mapToInt(Integer::intValue).toArray(), left, right, slope, GAP);
        }
    }
}
