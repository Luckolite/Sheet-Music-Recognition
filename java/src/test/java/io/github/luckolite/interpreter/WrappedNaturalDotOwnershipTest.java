// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class WrappedNaturalDotOwnershipTest {
    private static final int W = 180;
    private static final int H = 150;
    private static final int ACCIDENTAL = OmrMeasurePostProcessor.CLEF_OR_KEY;
    private static final WrappedTripletDotInk.Center CORE = new WrappedTripletDotInk.Center(64, 69);

    @Test
    public void connectedNaturalOwnsCrossbarButNotDetachedDot() {
        var paper = new byte[W * H];
        var labels = new byte[W * H];
        Arrays.fill(paper, (byte) 255);
        paint(paper, labels, 59, 62, 54, 93);
        paint(paper, labels, 70, 73, 67, 105);
        paint(paper, labels, 59, 73, 67, 73);
        paint(paper, labels, 59, 73, 84, 90);
        assertTrue(WrappedEighthTriplets.naturalOwnsDot(
                paper, labels, W, H, CORE, 92, 80, 16));

        paint(paper, labels, 53, 55, 68, 71);
        assertFalse(WrappedEighthTriplets.naturalOwnsDot(
                paper, labels, W, H, new WrappedTripletDotInk.Center(54, 69), 92, 80, 16));

        Arrays.fill(labels, (byte) 0);
        assertFalse(WrappedEighthTriplets.naturalOwnsDot(
                paper, labels, W, H, CORE, 92, 80, 16));
    }

    private static void paint(byte[] paper, byte[] labels, int x0, int x1, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            Arrays.fill(paper, y * W + x0, y * W + x1 + 1, (byte) 0);
            Arrays.fill(labels, y * W + x0, y * W + x1 + 1, (byte) ACCIDENTAL);
        }
    }
}
