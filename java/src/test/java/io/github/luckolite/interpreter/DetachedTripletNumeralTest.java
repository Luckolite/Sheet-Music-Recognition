// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class DetachedTripletNumeralTest {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 260;
    private static final float GAP = 16;

    @Test
    public void aForeignNumeralNeedsItsDetachedShaftOrHeadEndpoint() {
        byte[] pixels = paper();
        mark(pixels, 90, 151, 91, 200);
        mark(pixels, 91, 147, 107, 155);
        mark(pixels, 55, 197, 91, 200);
        // A digit just below the previous system beam and one just above this head.
        assertTrue(endpoint(pixels, 86, 207, 96, 222, Float.POSITIVE_INFINITY));
        assertTrue(endpoint(pixels, 95, 119, 105, 135, Float.POSITIVE_INFINITY));
        assertFalse(endpoint(pixels, 86, 207, 96, 222, 3));
        assertFalse(endpoint(pixels, 73, 207, 83, 222, Float.POSITIVE_INFINITY));

        for (int y = 167; y <= 188; y++)
            Arrays.fill(pixels, y * WIDTH + 87, y * WIDTH + 95, (byte) 255);
        assertFalse(endpoint(pixels, 86, 207, 96, 222, Float.POSITIVE_INFINITY));
        assertFalse(endpoint(pixels, 95, 119, 105, 135, Float.POSITIVE_INFINITY));
    }

    @Test
    public void aVerticalStrokeIsNotAnOpeningFingeringParenthesis() {
        byte[] curve = paper();
        byte[] stroke = paper();
        for (int y = 70; y <= 94; y++) {
            int x = 48 + Math.round(4 * (y - 82) * (y - 82) / 144f);
            mark(curve, x, y, x + 1, y);
            mark(stroke, 51, y, 52, y);
        }
        assertTrue(TripletRhythmDetector.openingFingerParenthesis(curve, WIDTH, HEIGHT, 55, 73, 90, GAP));
        assertFalse(TripletRhythmDetector.openingFingerParenthesis(stroke, WIDTH, HEIGHT, 55, 73, 90, GAP));
        assertFalse(TripletRhythmDetector.openingFingerParenthesis(curve, WIDTH, HEIGHT, 63, 73, 90, GAP));
    }

    private static boolean endpoint(byte[] pixels, int left, int top, int right, int bottom, float own) {
        return TripletRhythmDetector.foreignNumeralEndpoint(
                pixels, WIDTH, HEIGHT, 100, 150, -1, GAP, left, top, right, bottom, own);
    }

    private static byte[] paper() {
        byte[] pixels = new byte[WIDTH * HEIGHT];
        Arrays.fill(pixels, (byte) 255);
        return pixels;
    }

    private static void mark(byte[] pixels, int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(pixels, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
