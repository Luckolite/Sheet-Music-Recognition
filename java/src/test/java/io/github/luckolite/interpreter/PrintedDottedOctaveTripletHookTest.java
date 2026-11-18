// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PrintedDottedOctaveTripletHookTest {
    private static final int WIDTH = 200;
    private static final int HEIGHT = 180;
    private static final float GAP = 16;
    private static final PrintedBeamShafts.Shaft FIRST =
            new PrintedBeamShafts.Shaft(69, 70, 125, 80, -1, List.of());
    private static final PrintedBeamShafts.Shaft SECOND =
            new PrintedBeamShafts.Shaft(109, 110, 125, 80, -1, List.of());

    @Test
    public void aBackwardSecondaryHookDoesNotBecomeAThirdBeamAtAStaffRule() {
        var plane = new byte[WIDTH * HEIGHT];
        Arrays.fill(plane, (byte) 255);
        draw(plane, 70, 153, 81, 88);
        draw(plane, 97, 110, 94, 101);
        draw(plane, 108, 110, 82, 139);
        draw(plane, 50, 167, 111, 111);
        assertTrue(qualifies(plane));

        var third = plane.clone();
        draw(third, 97, 110, 107, 116);
        assertFalse(qualifies(third));

        var extendedSecondary = plane.clone();
        draw(extendedSecondary, 72, 100, 94, 101);
        assertFalse(qualifies(extendedSecondary));

        for (int y = 94; y <= 101; y++)
            Arrays.fill(plane, y * WIDTH + 95, y * WIDTH + 108, (byte) 255);
        assertFalse(qualifies(plane));
    }

    private static boolean qualifies(byte[] plane) {
        return PrintedDottedTripletBar.hasOnlySecondaryHook(plane, WIDTH, HEIGHT, FIRST, SECOND, GAP);
    }

    private static void draw(byte[] plane, int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(plane, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
