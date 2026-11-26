// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CompactCaptionAndHookTest {
    private static final int WIDTH = 180, HEIGHT = 125;
    private static final float GAP = 14;

    @Test
    public void aCroppedNumeralInACommonTextBaselineIsRefused() {
        byte[] raster = paper();
        rect(raster, 47, 31, 10, 16);
        rect(raster, 67, 31, 14, 16);
        rect(raster, 94, 34, 10, 13);
        rect(raster, 111, 31, 6, 16);
        assertTrue(caption(raster));
    }

    @Test
    public void anIsolatedNumeralPreservesItsEligibility() {
        byte[] raster = paper();
        rect(raster, 67, 31, 14, 16);
        rect(raster, 94, 54, 10, 13);
        rect(raster, 111, 51, 6, 16);
        assertFalse(caption(raster));
    }

    @Test
    public void aRisingHookIsDistinctFromAnOvalAndLedger() throws Exception {
        byte[] raster = paper();
        for (int x = 40; x <= 67; x++) {
            int run = Math.max(0, x - 54);
            int y = 77 - Math.round(.057f * run * run);
            rect(raster, x, y - 1, 1, 4);
        }
        assertTrue(hook(raster));
        byte[] ledger = paper();
        rect(ledger, 41, 77, 31, 2);
        rect(ledger, 54, 74, 12, 8);
        assertFalse(hook(ledger));
    }

    private static boolean caption(byte[] raster) {
        return TripletRhythmDetector.joinedNumeralSharesCaption(raster, WIDTH, HEIGHT, 66, 34, 74, 49, GAP);
    }

    private static boolean hook(byte[] raster) throws Exception {
        var type = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        var island = ctor.newInstance(20, 56, 61, 74, 79, 58.5f, 76.5f);
        var method = OmrScoreInterpreter.class.getDeclaredMethod("thinOpeningHook",
                byte[].class, int.class, int.class, type, float.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, raster, WIDTH, HEIGHT, island, GAP, 120);
    }

    private static byte[] paper() {
        byte[] raster = new byte[WIDTH * HEIGHT];
        Arrays.fill(raster, (byte) 255);
        return raster;
    }

    private static void rect(byte[] raster, int x, int y, int w, int h) {
        for (int yy = y; yy < y + h; yy++) Arrays.fill(raster, yy * WIDTH + x, yy * WIDTH + x + w, (byte) 0);
    }
}
