// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BorrowedDyadBeamMaskTest {
    private static final int WIDTH = 220, HEIGHT = 240;
    private static final float GAP = 16;
    private static final int LEFT = 74, MIDDLE = 108, RIGHT = 142;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final byte[] labels = new byte[WIDTH * HEIGHT];

    public BorrowedDyadBeamMaskTest() {
        Arrays.fill(gray, (byte) 255);
        for (int x = LEFT; x <= RIGHT; x++) {
            int tip = tip(x);
            column(x, tip - 7, tip, 0);
            column(x, tip - 19, tip - 12, 0);
            if (Math.abs(x - MIDDLE) <= 15) column(x, tip - 12, tip - 7, 0);
        }
        for (int shaft : new int[]{LEFT, MIDDLE, RIGHT}) {
            for (int x = shaft; x <= shaft + 2; x++) column(x, 65, tip(shaft), 0);
            head(shaft, 64);
            head(shaft, 113);
        }
        for (int x = MIDDLE - 15; x <= MIDDLE + 3; x++)
            for (int depth = 6; depth <= 12; depth++)
                labels[(tip(x) - depth) * WIDTH + x] = OmrMeasurePostProcessor.NOTEHEAD;
    }

    @Test
    public void requiresOwnedShaftsAndTwoRails() throws Exception {
        assertTrue(owned());
        for (int x = MIDDLE + 19; x <= MIDDLE + 25; x++)
            column(x, tip(x) - 19, tip(x) - 12, 255);
        assertFalse(owned());
    }

    @Test
    public void retainsMaskWithInkOutsideTheBeam() throws Exception {
        int first = MIDDLE - 15;
        for (int y = tip(first) - 21; y < tip(first) - 12; y++)
            labels[y * WIDTH + first] = OmrMeasurePostProcessor.NOTEHEAD;
        assertFalse(owned());
    }

    @Test
    public void widthAcrossAShaftRequiresBothSurroundingBeamRails() throws Exception {
        widenMask();
        assertTrue(ownedWide());

        // The two flanking white seams are part of the ownership proof.
        for (int x = LEFT + 4; x <= MIDDLE - 15; x++) column(x, tip(x) - 12, tip(x) - 7, 0);
        assertFalse(ownedWide());
    }

    @Test
    public void aWideMaskCannotBorrowAnInterruptedShaft() throws Exception {
        widenMask();
        assertTrue(ownedWide());
        for (int x = MIDDLE - 1; x <= MIDDLE + 3; x++) column(x, 134, 152, 255);
        assertFalse(ownedWide());
    }

    private void widenMask() {
        for (int x = MIDDLE - 15; x <= MIDDLE + 15; x++)
            for (int depth = 7; depth <= 13; depth++)
                labels[(tip(x) - depth) * WIDTH + x] = OmrMeasurePostProcessor.NOTEHEAD;
    }

    private boolean ownedWide() throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var component = Class.forName(interpreter.getName() + "$Component");
        Method components =
                interpreter.getDeclaredMethod(
                        "findComponents", byte[].class, int.class, int.class, byte.class);
        components.setAccessible(true);
        var heads =
                (List<?>)
                        components.invoke(
                                null, labels, WIDTH, HEIGHT, OmrMeasurePostProcessor.NOTEHEAD);
        Class<?> staff = Class.forName(interpreter.getName() + "$Staff");
        var staffConstructor = staff.getDeclaredConstructor(float.class, float.class, float.class);
        staffConstructor.setAccessible(true);
        Method owns =
                interpreter.getDeclaredMethod(
                        "wideMergedBeamShaftHeads",
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        List.class,
                        List.class,
                        java.util.Set.class);
        owns.setAccessible(true);
        var owners = new java.util.HashSet<>();
        var aliases =
                (List<?>)
                        owns.invoke(
                                null,
                                labels,
                                gray,
                                WIDTH,
                                HEIGHT,
                                heads,
                                List.of(staffConstructor.newInstance(116f, 180f, GAP)),
                                owners);
        if (aliases.isEmpty()) {
            assertTrue(owners.isEmpty());
            return false;
        }
        assertTrue(owners.size() == 2);
        var centerY = component.getDeclaredMethod("centerY");
        centerY.setAccessible(true);
        assertTrue(aliases.size() == 1 && (float) centerY.invoke(aliases.get(0)) > 150);
        return true;
    }

    private boolean owned() throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var component = Class.forName(interpreter.getName() + "$Component");
        Method components = interpreter.getDeclaredMethod(
                "findComponents", byte[].class, int.class, int.class, byte.class);
        components.setAccessible(true);
        var heads = (List<?>) components.invoke(null, labels, WIDTH, HEIGHT, OmrMeasurePostProcessor.NOTEHEAD);
        Method centerY = component.getDeclaredMethod("centerY");
        centerY.setAccessible(true);
        Object fragment = null;
        for (Object head : heads)
            if ((float) centerY.invoke(head) > 150) fragment = head;
        Method owns = interpreter.getDeclaredMethod("borrowedDenseDyadBeamMask", byte[].class,
                byte[].class, int.class, int.class, component, List.class, float.class, int[].class,
                boolean.class);
        owns.setAccessible(true);
        return (boolean) owns.invoke(null, labels, gray, WIDTH, HEIGHT, fragment, heads, GAP,
                new int[]{MIDDLE + 1, 64, -1}, false);
    }

    private static int tip(int x) {
        return 178 + Math.round((x - LEFT) / 8.5f);
    }

    private void head(int shaft, int y) {
        for (int x = shaft; x <= shaft + 19; x++) {
            column(x, y - 7, y + 7, 0);
            for (int row = y - 7; row <= y + 7; row++)
                labels[row * WIDTH + x] = OmrMeasurePostProcessor.NOTEHEAD;
        }
    }

    private void column(int x, int a, int b, int grayValue) {
        for (int y = a; y <= b; y++) gray[y * WIDTH + x] = (byte) grayValue;
    }
}
