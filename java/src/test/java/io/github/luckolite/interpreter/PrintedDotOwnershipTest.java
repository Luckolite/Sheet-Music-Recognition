// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Generated beams with an independent upper dot or printed fingering. */
public final class PrintedDotOwnershipTest {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 154;
    private static final float GAP = 16f;

    @Test
    public void consecutiveLowUpperStaccatosBelongToTheirOwnHeads() throws Exception {
        assertEquals(0, descending(true, true));
    }

    @Test
    public void isolatedEvidenceKeepsThePossibleAugmentation() throws Exception {
        assertEquals(1, descending(false, true));
        assertEquals(1, descending(true, false));
    }

    @Test
    public void joinedFingeringCoreBelongsToTheCompleteDigit() throws Exception {
        assertEquals(0, fingering(true, true));
    }

    @Test
    public void aRoundCoreAloneOrWithoutSharedBeamsRemainsAnAugmentation() throws Exception {
        assertEquals(1, fingering(false, true));
        assertEquals(1, fingering(true, false));
    }

    private static int descending(boolean corroboration, boolean connected) throws Exception {
        byte[] gray = paper();
        Object origin = drawHead(gray, 59, 59);
        Object next = drawHead(gray, 90, 69);
        Object third = drawHead(gray, 122, 76);
        rails(gray, 48, 113);
        round(gray, 59, 42);
        round(gray, 90, 52);
        if (corroboration) round(gray, 122, 58);
        if (!connected) rectangle(gray, 94, 108, 105, 134, 255);
        return count(gray, origin, List.of(origin, next, third));
    }

    private static int fingering(boolean fullGlyph, boolean connected) throws Exception {
        byte[] gray = paper();
        Object origin = drawHead(gray, 62, 52);
        Object next = drawHead(gray, 92, 74);
        rails(gray, 52, 85);
        if (!connected) rectangle(gray, 64, 110, 72, 134, 255);
        oval(gray, 85, 47, 2, 3);
        if (fullGlyph) drawDigitTwo(gray);
        return count(gray, origin, List.of(origin, next));
    }

    private static void drawDigitTwo(byte[] gray) {
        String[] rows = {
            "     #####    ",
            "    ########  ",
            "   +++####### ",
            "         #####",
            "         #####",
            "         #####",
            "        ##### ",
            "        ####  ",
            "       ####   ",
            "      ####    ",
            "     ####     ",
            "    ####      ",
            "   ####       ",
            "  ####        ",
            " ####       ##",
            "####       ###",
            "############# ",
            "############# ",
            "############  "
        };
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                char shade = rows[y].charAt(x);
                if (shade != ' ') gray[(42 + y) * WIDTH + 83 + x] = (byte) (shade == '#' ? 0 : 175);
            }
        }
    }

    private static int count(byte[] gray, Object origin, List<Object> neighborhood)
            throws Exception {
        Class<?> component = origin.getClass();
        var method =
                OmrScoreInterpreter.class.getDeclaredMethod(
                        "countAugmentationDots",
                        List.class,
                        component,
                        float.class,
                        byte[].class,
                        int.class,
                        int.class,
                        boolean.class,
                        List.class,
                        List.class);
        method.setAccessible(true);
        return (int)
                method.invoke(
                        null,
                        List.of(),
                        origin,
                        GAP,
                        gray,
                        WIDTH,
                        HEIGHT,
                        false,
                        List.of(),
                        neighborhood);
    }

    private static Object drawHead(byte[] gray, int x, int y) throws Exception {
        oval(gray, x, y, 10, 8);
        rectangle(gray, x - 10, y + 1, x - 7, 131, 0);
        Class<?> component = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var constructor = component.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(252, x - 10, x + 10, y - 8, y + 8, (float) x, (float) y);
    }

    private static byte[] paper() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        return gray;
    }

    private static void rails(byte[] gray, int left, int right) {
        rectangle(gray, left, 125, right, 131, 0);
        rectangle(gray, left, 113, right, 119, 0);
    }

    private static void round(byte[] gray, int x, int y) {
        oval(gray, x, y, 3, 3);
    }

    private static void rectangle(
            byte[] gray, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
        }
    }

    private static void oval(byte[] gray, int x, int y, int rx, int ry) {
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dy = -ry; dy <= ry; dy++) {
                if (dx * dx * ry * ry + dy * dy * rx * rx <= rx * rx * ry * ry) {
                    gray[(y + dy) * WIDTH + x + dx] = 0;
                }
            }
        }
    }
}
