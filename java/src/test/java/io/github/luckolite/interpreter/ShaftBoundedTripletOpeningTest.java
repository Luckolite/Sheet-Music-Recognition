// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ShaftBoundedTripletOpeningTest {
    private static final int W = 840, H = 330;

    @Test
    public void firstDownshaftCannotCloseTheIncomingStaffPocket() {
        var ink = new byte[W * H];
        Arrays.fill(ink, (byte)255);
        for (int y = 184; y <= 248; y += 16) paint(ink, 20, 805, y, y + 1, 0);
        paint(ink, 127, 130, 180, 264, 0);
        paint(ink, 639, 641, 184, 248, 0);
        var bars = List.of(region(20, 806, 10, 92), region(80, 635, 150, 281),
                region(652, 806, 150, 281));

        assertFalse(WrappedMeasureBoundaries.confirmsInitialStaffBar(ink, W, H, bars, 1, 140, 605));
        assertTrue(WrappedMeasureBoundaries.confirmsInitialStaffBar(ink, W, H, bars, 1, 140, 605, 127));

        var otherStroke = ink.clone();
        paint(otherStroke, 111, 113, 184, 248, 0);
        assertFalse(WrappedMeasureBoundaries.confirmsInitialStaffBar(
                otherStroke, W, H, bars, 1, 140, 605, 127));
        var missingEnd = ink.clone();
        paint(missingEnd, 638, 642, 186, 246, 255);
        assertFalse(WrappedMeasureBoundaries.confirmsInitialStaffBar(
                missingEnd, W, H, bars, 1, 140, 605, 127));
    }

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion(left / (float) W, right / (float) W, top / (float) H, bottom / (float) H);
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom, int shade) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * W + left, y * W + right + 1, (byte) shade);
    }
}
