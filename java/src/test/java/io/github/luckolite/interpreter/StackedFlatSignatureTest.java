// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class StackedFlatSignatureTest {
    private static final int WIDTH = 330;
    private static final int HEIGHT = 200;
    private static final int TOP = 57;
    private static final float GAP = 20f;

    @Test
    public void fourSeparateBowlsInTwoColumnsProveTheEngravedSignature() {
        byte[] gray = opening(105, true, true);
        assertTrue(PrintedFlatGlyph.stackedTrebleSignature(gray, WIDTH, HEIGHT, 96, 137, GAP));
    }

    @Test
    public void requiresAllFourPitchPositionsAndASeparateCommonTimeSign() {
        assertFalse(PrintedFlatGlyph.stackedTrebleSignature(
                opening(105, false, true), WIDTH, HEIGHT, 96, 137, GAP));
        assertFalse(PrintedFlatGlyph.stackedTrebleSignature(
                opening(105, true, false), WIDTH, HEIGHT, 96, 137, GAP));
    }

    private static byte[] opening(int meterLeft, boolean fourthBowl, boolean commonTime) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        flat(gray, 50, 66);
        flat(gray, 50, 95);
        flat(gray, 80, 75);
        if (fourthBowl) flat(gray, 80, 106);
        if (commonTime) {
            for (int y = TOP + 13; y <= TOP + 67; y++) {
                for (int x = meterLeft; x <= meterLeft + 43; x++) {
                    double angle = Math.atan2((y - TOP - 40) / 25.0, (x - meterLeft - 21) / 20.0);
                    double radius = Math.hypot((y - TOP - 40) / 25.0, (x - meterLeft - 21) / 20.0);
                    if (radius >= .66 && radius <= 1.02 && Math.abs(angle) > .54)
                        gray[y * WIDTH + x] = 0;
                }
            }
        }
        for (int i = 0; i < 5; i++) {
            int y = TOP + i * 20;
            Arrays.fill(gray, y * WIDTH, (y + 1) * WIDTH, (byte) 0);
        }
        return gray;
    }

    private static void flat(byte[] gray, int left, int center) {
        for (int y = center - 20; y <= center + 7; y++)
            for (int x = left; x < left + 3; x++) gray[y * WIDTH + x] = 0;
        for (int y = center - 5; y <= center + 5; y++) {
            for (int x = left + 3; x <= left + 10; x++) {
                if (Math.abs(y - center) == 5 || x >= left + 9) gray[y * WIDTH + x] = 0;
            }
        }
    }
}
