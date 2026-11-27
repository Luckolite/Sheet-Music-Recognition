// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class FiniteStaffBarlineTest {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 330;
    private static final int X = 150;
    private static final int GAP = 14;
    private static final int[] ROWS = {220, 234, 248, 262, 276};

    @Test
    public void recognizesShortTailBesideAnAccidental() {
        byte[] raster = staff();
        for (int y = 220; y <= 288; y++) ink(raster, X, y);
        for (int x = X - 3; x <= X + 13; x++) {
            ink(raster, x, 274);
            ink(raster, x, 283);
        }
        for (int y = 266; y <= 288; y++) ink(raster, X + 9, y);
        assertTrue(detect(raster));
    }

    @Test
    public void checksFarEndOfWideSharedBarline() {
        byte[] raster = staff();
        for (int y = 40; y <= 276; y++) ink(raster, X, y);
        assertFalse(detect(raster));
        for (int row = 40; row <= 96; row += GAP) rule(raster, row);
        assertTrue(detect(raster));
    }

    @Test
    public void rejectsStemWithPhysicalHeadAtItsEnd() {
        byte[] raster = staff();
        for (int y = 220; y <= 276; y++) ink(raster, X, y);
        for (int y = 270; y <= 282; y++) {
            for (int x = X - 15; x <= X; x++) {
                double dx = (x - (X - 7)) / 8.0;
                double dy = (y - 276) / 7.0;
                if (dx * dx + dy * dy <= 1) ink(raster, x, y);
            }
        }
        assertFalse(detect(raster));
    }

    @Test
    public void nearbyLedgerNoteCannotClaimASeparatedStaffHeightBar() {
        assertTrue(barWithLedgerHead(false));
    }

    @Test
    public void ledgerHeadStillOwnsARuleJoinedToItsStem() {
        assertFalse(barWithLedgerHead(true));
    }

    private static boolean barWithLedgerHead(boolean attached) {
        byte[] raster = staff(), labels = new byte[raster.length];
        for (int y = attached ? 202 : ROWS[0]; y <= ROWS[4]; y++) ink(raster, X, y);
        for (int x = X - 15; x <= X + 1; x++) ink(raster, x, 201);
        for (int y = 196; y <= 206; y++)
            for (int x = X - 12; x <= X + 1; x++) {
                double dx = (x - (X - 5)) / 7.0, dy = (y - 201) / 5.0;
                if (dx * dx + dy * dy > 1) continue;
                ink(raster, x, y);
                labels[y * WIDTH + x] = OmrMeasurePostProcessor.NOTEHEAD;
            }
        return OmrMeasurePostProcessor.provesFiniteBarline(
                labels, raster, WIDTH, HEIGHT, X, ROWS, GAP, 0);
    }

    private static boolean detect(byte[] raster) {
        return OmrMeasurePostProcessor.provesFiniteBarline(null, raster, WIDTH, HEIGHT, X, ROWS, GAP, 0);
    }

    private static byte[] staff() {
        byte[] raster = new byte[WIDTH * HEIGHT];
        Arrays.fill(raster, (byte) 255);
        for (int row : ROWS) rule(raster, row);
        return raster;
    }

    private static void rule(byte[] raster, int row) {
        for (int x = 25; x < WIDTH - 25; x++) ink(raster, x, row);
    }

    private static void ink(byte[] raster, int x, int y) {
        raster[y * WIDTH + x] = 0;
    }
}
