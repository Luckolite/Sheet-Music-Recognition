// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OpeningTripletContextInkTest {
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 640;
    private static final int GAP = 16;

    @Test
    public void completeCurvesDoNotHideAnIndependentMark() {
        var page = new Page();
        var result = page.examine(page.raw.clone());
        assertInk(result.original(), 368, 230, 255);
        assertInk(result.corrected(), 368, 230, 255);
        assertInk(page.raw, 368, 230, 0);
        assertInk(result.original(), 375, 181, 0);
        assertInk(result.original(), 360, 355, 0);
    }

    @Test
    public void anUnpairedCurveStaysInTheMarkSearch() {
        var page = new Page();
        var other = page.raw.clone();
        for (int y = 226; y <= 263; y++)
            Arrays.fill(other, y * WIDTH + 230, y * WIDTH + 511, (byte) 255);
        assertInk(page.examine(other).original(), 368, 230, 0);
    }

    private static final class Page {
        final byte[] raw = new byte[WIDTH * HEIGHT];
        final float[] headsX = new float[18];
        final float[] headsY = new float[18];

        Page() {
            Arrays.fill(raw, (byte) 255);
            for (int i = 0; i < headsX.length; i++) {
                headsX[i] = 200 + 32 * i;
                headsY[i] = 300;
            }
            for (int x = 240; x <= 496; x++) {
                double rel = (x - 368) / 128.0;
                int y = 230 + (int) Math.round(27 * rel * rel);
                for (int row = y - 1; row <= y + 1; row++) raw[row * WIDTH + x] = 0;
            }
            for (int row = 180; row < 188; row++)
                Arrays.fill(raw, row * WIDTH + 370, row * WIDTH + 382, (byte) 0);
            Arrays.fill(raw, 355 * WIDTH + 320, 355 * WIDTH + 408, (byte) 0);
        }

        OpeningTripletContextInk.Pair examine(byte[] corrected) {
            return OpeningTripletContextInk.withoutContext(raw, corrected, WIDTH, HEIGHT, GAP,
                    headsX, headsY, List.of());
        }
    }

    private static void assertInk(byte[] data, int x, int y, int expected) {
        assertEquals(expected, data[y * WIDTH + x] & 255);
    }
}
