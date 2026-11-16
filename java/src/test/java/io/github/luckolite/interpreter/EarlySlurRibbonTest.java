// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class EarlySlurRibbonTest {
    private static final int WIDTH = 256, HEIGHT = 140;
    private static final int NUMERAL_RIGHT = 62, NUMERAL_TOP = 24;
    private static final float GAP = 16;

    @Test
    public void requiresTheCrossingAndADottedTerminalTurn() {
        var score = paper();
        float end = end(score);
        assertTrue(end > 193 && end < 201);

        var withoutStem = score.clone();
        fill(withoutStem, 187, 194, 35, 64, 255);
        assertEquals(-1, end(withoutStem), 0);

        var withoutSlur = score.clone();
        fill(withoutSlur, 63, 94, 15, 39, 255);
        fill(withoutSlur, 65, 70, 29, 32, 0);
        assertEquals(-1, end(withoutSlur), 0);

        var tooFewDots = score.clone();
        fill(tooFewDots, 129, 193, 28, 34, 255);
        assertEquals(-1, end(tooFewDots), 0);
    }

    @Test
    public void largeStaffGapDoesNotExceedComponentCropLimit() {
        int width = 400, height = 170;
        var ink = new byte[width * height];
        Arrays.fill(ink, (byte) 255);
        assertEquals(-1, OctaveMarkDetector.earlySlurRibbonEnd(
                ink, width, height, NUMERAL_RIGHT, NUMERAL_TOP, 24), 0);
    }

    private static float end(byte[] ink) {
        return OctaveMarkDetector.earlySlurRibbonEnd(
                ink, WIDTH, HEIGHT, NUMERAL_RIGHT, NUMERAL_TOP, GAP);
    }

    private static byte[] paper() {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        fill(ink, 65, 70, 29, 32, 0);
        fill(ink, 63, 72, 21, 24, 0);
        for (int x = 69; x <= 94; x++) {
            int y = Math.round(23 + (x - 69) * .50f);
            fill(ink, x, x, y - 1, y + 1, 0);
        }
        fill(ink, 76, 88, 30, 32, 0);
        for (int x = 95; x <= 185; x += 9) fill(ink, x, x + 5, 29, 33, 0);
        fill(ink, 188, 192, 38, 41, 0);
        fill(ink, 188, 192, 47, 50, 0);
        fill(ink, 188, 192, 56, 59, 0);
        return ink;
    }

    private static void fill(byte[] ink, int left, int right, int top, int bottom, int color) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte) color);
    }
}
