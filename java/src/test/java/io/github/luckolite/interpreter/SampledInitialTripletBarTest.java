// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class SampledInitialTripletBarTest {
    private static final int WIDTH = 920, HEIGHT = 345;
    private static final int TOP = 170, BOTTOM = 234, GAP = 16;
    private static final List<MeasureRegion> BARS = List.of(
            region(150, 720, 14, 73), region(160, 720, 134, 268), region(738, 866, 134, 268));

    @Test
    public void nearbyFiveRuleSamplesCanCloseAStaffOpeningWithAnIndependentDivider() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += GAP)
            fill(gray, 32, WIDTH - 20, y, y + 1, 0);
        fill(gray, 439, 474, 202, 203, 255);
        fill(gray, 556, 720, 202, 203, 255);
        fill(gray, 729, 732, TOP, BOTTOM + 1, 0);

        assertFalse(WrappedMeasureBoundaries.confirmsInitialStaffBar(gray, WIDTH, HEIGHT, BARS, 1, 240, 690, 228));
        assertTrue(WrappedMeasureBoundaries.confirmsSampledInitialTripletBar(
                gray, WIDTH, HEIGHT, BARS, 1, 240, 690, 228));
        fill(gray, 728, 733, TOP, BOTTOM + 2, 255);
        assertFalse(WrappedMeasureBoundaries.confirmsSampledInitialTripletBar(
                gray, WIDTH, HEIGHT, BARS, 1, 240, 690, 228));
    }

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH, top / (float) HEIGHT,
                bottom / (float) HEIGHT);
    }

    private static void fill(byte[] gray, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
