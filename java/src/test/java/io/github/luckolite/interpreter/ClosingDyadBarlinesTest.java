// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClosingDyadBarlinesTest {
    private static final int WIDTH = 270;
    private static final int HEIGHT = 180;
    private static final int GAP = 16;

    @Test
    public void fullHeightStemBelongsToDyadAndTwoRails() {
        var left = new ClosingDyadBarlines.Dyad(100, 60, 76);
        var right = new ClosingDyadBarlines.Dyad(162, 68, 84);
        var staff = new RawStaffLineDetector.StaffLines(new int[] {60, 76, 92, 108, 124}, GAP);
        assertTrue(ClosingDyadBarlines.attachedToRail(left, right, 87, 96, staff, ink(true), WIDTH, HEIGHT));
        assertFalse(ClosingDyadBarlines.attachedToRail(left, right, 87, 96, staff, ink(false), WIDTH, HEIGHT));
        byte[] divided = ink(true);
        for (int x = 87; x <= 88; x++)
            for (int y = 60; y <= 124; y++) divided[y * WIDTH + x] = 0;
        assertFalse(ClosingDyadBarlines.attachedToRail(left, right, 87, 96, staff, divided, WIDTH, HEIGHT));
    }

    private static byte[] ink(boolean secondary) {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int row = 60; row <= 124; row += GAP) {
            for (int x = 28; x <= 230; x++) {
                ink[row * WIDTH + x] = 0;
                ink[(row + 1) * WIDTH + x] = 0;
            }
        }
        drawDyad(ink, 100, 60, 131);
        drawDyad(ink, 162, 68, 137);
        for (int x = 91; x <= 153; x++) {
            int end = 131 + Math.round((x - 91) * 6 / 62f);
            for (int y = end - 6; y <= end; y++) ink[y * WIDTH + x] = 0;
            if (secondary)
                for (int y = end - 17; y <= end - 12; y++) ink[y * WIDTH + x] = 0;
        }
        return ink;
    }

    private static void drawDyad(byte[] ink, int x, int y, int end) {
        for (int a = x - 10; a <= x - 8; a++) {
            for (int b = y + 2; b <= end; b++) ink[b * WIDTH + a] = 0;
        }
        for (int center : new int[] {y, y + GAP}) {
            for (int dx = -9; dx <= 9; dx++)
                for (int dy = -6; dy <= 6; dy++)
                    if (dx * dx / 90f + dy * dy / 41f <= 1f)
                        ink[(center + dy) * WIDTH + x + dx] = 0;
        }
    }
}
