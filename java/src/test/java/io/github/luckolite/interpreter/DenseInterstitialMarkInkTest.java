// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class DenseInterstitialMarkInkTest {
    private static final int WIDTH = 210;
    private static final int HEIGHT = 180;
    private static final int X = 103;
    private static final int Y = 91;
    private static final int GAP = 16;

    @Test
    public void denseInkOccupiesTheSpaceBeyondTheHeadBody() {
        var plane = paper();
        fill(plane, X - 8, X + 8, Y - 21, Y + 10, (byte) 5);
        assertTrue(owns(plane));
    }

    @Test
    public void genuineOvalAndOrdinaryStaffLinesRemainEligible() {
        var plane = paper();
        for (int dy = -7; dy <= 7; dy++) {
            int w = (int) Math.round(Math.sqrt(1 - dy * dy / 64.0) * 11);
            fill(plane, X - w, X + w, Y + dy, Y + dy, (byte) 0);
        }
        assertFalse(owns(plane));
    }

    private static boolean owns(byte[] gray) {
        return ClosedDottedThirds.fillsPastHead(gray, WIDTH, HEIGHT, X, Y, GAP);
    }

    private static byte[] paper() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 55; y <= 119; y += GAP) {
            fill(gray, 20, WIDTH - 22, y, y + 1, (byte) 0);
        }
        return gray;
    }

    private static void fill(byte[] gray, int left, int right, int top, int bottom, byte value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, value);
        }
    }
}
