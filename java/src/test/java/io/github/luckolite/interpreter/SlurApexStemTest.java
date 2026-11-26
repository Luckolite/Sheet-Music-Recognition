// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original drawn slur and same-footprint controls. */
public final class SlurApexStemTest {
    private static final int WIDTH = 260, HEIGHT = 210, TIP_X = 128, TIP_Y = 75;
    private static final float GAP = 16;

    @Test
    public void twoReturningArmsAreDistinctFromAFlagAtTheTip() {
        assertTrue(proves(arc(true, false), true));
        assertTrue(proves(arc(false, false), false));
    }

    @Test
    public void oneArmCannotProveAnApex() {
        assertFalse(proves(arc(true, true), true));
    }

    @Test
    public void twoFlatOrStraightObliqueRailsCannotProveAnApex() {
        for (int slope : new int[] {0, 1}) {
            byte[] raster = paper();
            for (int dx = -56; dx <= 56; dx++) {
                int y = TIP_Y + slope * dx / 4;
                pixels(raster, TIP_X + dx, y);
            }
            assertFalse(proves(raster, true));
        }
    }

    private static boolean proves(byte[] raster, boolean up) {
        return OmrScoreInterpreter.slurApexAtStem(raster, WIDTH, HEIGHT, TIP_X, TIP_Y, up, GAP);
    }

    private static byte[] arc(boolean up, boolean singleArm) {
        byte[] raster = paper();
        int side = up ? 1 : -1;
        for (int dx = singleArm ? 0 : -56; dx <= 56; dx++) {
            int curve = (int) Math.round(dx * dx / 203.0);
            pixels(raster, TIP_X + dx, TIP_Y + side * curve);
        }
        for (int dy = 4; dy < 58; dy++)
            for (int dx = -1; dx <= 1; dx++) raster[(TIP_Y + side * dy) * WIDTH + TIP_X + dx] = 0;
        return raster;
    }

    private static byte[] paper() {
        byte[] raster = new byte[WIDTH * HEIGHT];
        Arrays.fill(raster, (byte) 255);
        return raster;
    }

    private static void pixels(byte[] raster, int x, int y) {
        for (int dy = -2; dy <= 2; dy++) raster[(y + dy) * WIDTH + x] = 0;
    }
}
