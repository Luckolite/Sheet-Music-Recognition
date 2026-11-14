// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class BrokenClosingDyadBeamTest {
    private static final int W = 220;
    private static final int H = 140;
    private static final int GAP = 16;

    @Test
    public void requiresSharedBrokenShaftOnOnlyThePrimaryRail() {
        var raw = new byte[W * H];
        Arrays.fill(raw, (byte) 255);
        var heads =
                new ClosedFourDyadBeam.Head[] {
                    new ClosedFourDyadBeam.Head(48, 43, 59),
                    new ClosedFourDyadBeam.Head(99, 52, 68),
                    new ClosedFourDyadBeam.Head(151, 59, 75)
                };
        for (var head : heads) {
            oval(raw, Math.round(head.x()), Math.round(head.top()));
            oval(raw, Math.round(head.x()), Math.round(head.bottom()));
        }
        rectangle(raw, 37, 43, 39, 111, 0);
        rectangle(raw, 88, 52, 90, 116, 0);
        rectangle(raw, 140, 59, 141, 99, 0);
        rectangle(raw, 140, 106, 141, 107, 0);
        drawRail(raw, 37, 141, 0);
        drawRail(raw, 37, 90, 13);
        assertTrue(ClosedFourDyadBeam.provesTwoSixteenthsAndEighth(raw, W, H, heads, GAP));

        var detached = raw.clone();
        rectangle(detached, 138, 104, 144, 109, 255);
        assertFalse(ClosedFourDyadBeam.provesTwoSixteenthsAndEighth(detached, W, H, heads, GAP));

        var continuousSecondary = raw.clone();
        drawRail(continuousSecondary, 91, 141, 13);
        assertFalse(
                ClosedFourDyadBeam.provesTwoSixteenthsAndEighth(
                        continuousSecondary, W, H, heads, GAP));

        var severed = raw.clone();
        rectangle(severed, 109, 109, 118, 125, 255);
        assertFalse(ClosedFourDyadBeam.provesTwoSixteenthsAndEighth(severed, W, H, heads, GAP));
    }

    @Test
    public void acceptsOnlyWholeTripletClaimsWithinAProvedFourDyadGroup() {
        assertTrue(ClosedSixteenthDyads.hasContainedTripletClaims(0b0111));
        assertTrue(ClosedSixteenthDyads.hasContainedTripletClaims(0b1110_0000_0111));
        assertTrue(ClosedSixteenthDyads.hasContainedTripletClaims(0b1110_0000));
        assertFalse(ClosedSixteenthDyads.hasContainedTripletClaims(0));
        assertFalse(ClosedSixteenthDyads.hasContainedTripletClaims(0b0110));
        assertFalse(ClosedSixteenthDyads.hasContainedTripletClaims(0b0011_1000));
        assertFalse(ClosedSixteenthDyads.hasContainedTripletClaims(0b0111_0000_0000_0000));
        assertFalse(ClosedSixteenthDyads.hasContainedTripletClaims(0b1111));
    }

    private static void drawRail(byte[] raw, int first, int last, int offset) {
        for (int x = first; x <= last; x++) {
            int y = 106 + Math.round((x - 37) * .135f) - offset;
            rectangle(raw, x, y - 4, x, y + 4, 0);
        }
    }

    private static void oval(byte[] raw, int x, int y) {
        for (int dy = -6; dy <= 6; dy++)
            for (int dx = -10; dx <= 10; dx++)
                if (dx * dx + 2 * dy * dy <= 100) raw[(y + dy) * W + x + dx] = 0;
    }

    private static void rectangle(byte[] raw, int left, int top, int right, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(raw, y * W + left, y * W + right + 1, (byte) tone);
    }
}
