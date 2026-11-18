// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class DetachedOctaveDashPairTest {
    private static final int WIDTH = 110;
    private static final int HEIGHT = 125;
    private static final float GAP = 16;

    @Test
    public void aDetachedPairMustContinueTheTerminalDashesDownward() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 62, 66, 52, 54);
        paint(ink, 72, 76, 52, 54);
        var lineOnly = ink.clone();
        paint(ink, 75, 77, 69, 72);
        paint(ink, 75, 77, 77, 82);
        assertTrue(downwardPair(ink));
        assertFalse(downwardPair(lineOnly));

        var single = lineOnly.clone();
        paint(single, 75, 77, 69, 72);
        assertFalse(downwardPair(single));
        var noteStem = lineOnly.clone();
        paint(noteStem, 75, 77, 68, 92);
        assertFalse(downwardPair(noteStem));
        var upward = lineOnly.clone();
        paint(upward, 75, 77, 34, 37);
        paint(upward, 75, 77, 24, 29);
        assertFalse(downwardPair(upward));
    }

    private static boolean downwardPair(byte[] page) {
        return OctaveMarkDetector.detachedDownwardDashPair(
                page, WIDTH, HEIGHT, 76 + GAP * .55f, 48, 57, GAP);
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
