// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class LedgerCoveredFourthTest {
    private static final int WIDTH = 210;
    private static final int HEIGHT = 217;
    private static final int GAP = 18;
    private static final int X = 105;
    private static final int Y = 114;
    private static final int TOUCH = Y - 27;

    @Test
    public void ledgerFilledDiamondNeedsItsOwnSharedShaftInBothPlanes() {
        byte[] labels = new byte[WIDTH * HEIGHT];
        byte[] gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        for (int dy = -12; dy <= 12; dy++)
            for (int dx = -12 + Math.abs(dy); dx <= 12 - Math.abs(dy); dx++)
                ink(labels, gray, X + dx, TOUCH + dy, OmrMeasurePostProcessor.SYMBOL);
        for (int row = TOUCH - 1; row <= TOUCH + 2; row++)
            for (int col = X - 22; col <= X + 22; col++)
                ink(labels, gray, col, row, OmrMeasurePostProcessor.SYMBOL);
        for (int dy = -8; dy <= 8; dy++) for (int dx = -11; dx <= 11; dx++)
            if (dx * dx / 121f + dy * dy / 64f <= 1)
                ink(labels, gray, X + dx, Y + dy, OmrMeasurePostProcessor.NOTEHEAD);
        for (int row = TOUCH; row <= Y + 55; row++) for (int col = X - 11; col <= X - 9; col++)
            ink(labels, gray, col, row, (byte) 1);
        assertTrue(ArtificialHarmonics.beamedLedgerFourth(labels, gray, gray, WIDTH, HEIGHT, X, Y, GAP));

        byte[] cut = gray.clone();
        for (int y = TOUCH + 9; y <= TOUCH + 14; y++)
            Arrays.fill(cut, y * WIDTH + X - 12, y * WIDTH + X - 7, (byte) 255);
        assertFalse(ArtificialHarmonics.beamedLedgerFourth(labels, gray, cut, WIDTH, HEIGHT, X, Y, GAP));
    }

    private static void ink(byte[] labels, byte[] gray, int x, int y, byte label) {
        labels[y * WIDTH + x] = label;
        gray[y * WIDTH + x] = 0;
    }
}
