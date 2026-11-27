// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class DownstemInnerLedgerContrastTest {
    private static final int WIDTH = 126, HEIGHT = 120, ROW = 60, SHAFT = 39;

    @Test
    public void provedShaftAndFadingFlankPreserveTheThinInnerLedger() {
        byte[] gray = raster();
        assertFalse(BilateralLedgerContrast.proved(gray, WIDTH, HEIGHT, 40, 68, ROW, 16));
        assertTrue(BilateralLedgerContrast.proved(gray, WIDTH, HEIGHT, 40, 68, ROW, 16, SHAFT));
    }

    @Test
    public void refusesLostConnectionOrMissingSecondFlank() {
        byte[] gray = raster();
        gray[ROW * WIDTH + 54] = (byte) 255;
        assertFalse(BilateralLedgerContrast.proved(gray, WIDTH, HEIGHT, 40, 68, ROW, 16, SHAFT));
        gray = raster();
        Arrays.fill(gray, ROW * WIDTH + 69, ROW * WIDTH + 74, (byte) 255);
        assertFalse(BilateralLedgerContrast.proved(gray, WIDTH, HEIGHT, 40, 68, ROW, 16, SHAFT));
    }

    private static byte[] raster() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        Arrays.fill(gray, ROW * WIDTH + 35, ROW * WIDTH + 73, (byte) 165);
        gray[ROW * WIDTH + 71] = (byte) 205;
        for (int y = ROW - 9; y <= ROW + 9; y++)
            Arrays.fill(gray, y * WIDTH + SHAFT, y * WIDTH + SHAFT + 5, (byte) 108);
        gray[ROW * WIDTH + 44] = (byte) 194;
        return gray;
    }
}
