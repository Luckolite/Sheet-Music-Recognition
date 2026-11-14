// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class PairedFlatTripletDotTest {
    private static final int WIDTH = 142;
    private static final int HEIGHT = 140;
    private static final float GAP = 16;
    private static final int LEFT = 59;
    private static final int RIGHT = 77;
    private static final int FOOT = 73;
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    public PairedFlatTripletDotTest() {
        Arrays.fill(gray, (byte) 255);
        drawFlat(LEFT);
        drawFlat(RIGHT);
        fill(10, 125, 64, 65, 0);
    }

    @Test
    public void firstFlatFootRequiresBothPeerSpinesAndBowls() {
        var core = new WrappedTripletDotInk.Center(LEFT + 2, FOOT);
        assertTrue(WrappedTripletDotInk.pairedFlatOwns(gray, WIDTH, HEIGHT, core, GAP));
        fill(RIGHT + 6, RIGHT + 14, FOOT - 18, FOOT - 7, 255);
        assertFalse(WrappedTripletDotInk.pairedFlatOwns(gray, WIDTH, HEIGHT, core, GAP));
    }

    @Test
    public void independentAugmentationDotKeepsItsOwnInk() {
        fill(53 - 2, 53 + 2, FOOT - 2, FOOT + 2, 0);
        var discovered =
                WrappedTripletDotInk.uniqueCore(gray, WIDTH, HEIGHT, 37, FOOT + 5, 101, GAP);
        assertNotNull(discovered);
        assertTrue(Math.abs(discovered.x() - 53) <= 1 && discovered.y() == FOOT);
        assertFalse(WrappedTripletDotInk.pairedFlatOwns(gray, WIDTH, HEIGHT, discovered, GAP));
        assertTrue(
                WrappedTripletDotInk.pairedFlatOwns(
                        gray, WIDTH, HEIGHT, new WrappedTripletDotInk.Center(LEFT + 2, FOOT), GAP));
    }

    private void drawFlat(int x) {
        fill(x, x + 2, FOOT - 32, FOOT + 2, 0);
        fill(x + 2, x + 10, FOOT - 18, FOOT - 16, 0);
        fill(x + 8, x + 13, FOOT - 16, FOOT - 9, 0);
        for (int i = 0; i < 8; i++) fill(x + i, x + i + 3, FOOT - i, FOOT - i + 1, 0);
    }

    private void fill(int left, int right, int top, int bottom, int ink) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) ink);
    }
}
