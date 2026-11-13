// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class StemOwnedBeamletTest {
    private static final int WIDTH = 100, HEIGHT = 150, X = 50, TIP = 75, GAP = 16;

    private byte[] drawing(int slopeSign, int direction, int beamletStart, int beamletEnd) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int dx = -32; dx <= 32; dx++) {
            int distance = 5 + Math.round(dx * slopeSign * .24f);
            for (int thickness = -4; thickness <= 4; thickness++) {
                paint(gray, X + dx, distance + thickness, direction);
                if (dx >= beamletStart && dx <= beamletEnd)
                    paint(gray, X + dx, distance + thickness + 13, direction);
            }
        }
        for (int d = 0; d < 55; d++) paint(gray, X, d, direction);
        return gray;
    }

    private void paint(byte[] gray, int x, int distance, int direction) {
        gray[(TIP - direction * distance) * WIDTH + x] = 0;
    }

    @Test
    public void ownsShortSecondaryBeamletOnEitherStemDirection() {
        for (int direction : new int[] {-1, 1}) {
            for (int slope : new int[] {-1, 1}) {
                assertTrue(StemOwnedBeamlet.proves(
                        drawing(slope, direction, -12, 0), WIDTH, HEIGHT,
                        new int[] {X, TIP, direction}, GAP));
            }
        }
    }

    @Test
    public void refusesDetachedInkAndAThinStaffRule() {
        var shaft = new int[] {X, TIP, 1};
        assertFalse(StemOwnedBeamlet.proves(drawing(1, 1, -12, -3), WIDTH, HEIGHT, shaft, GAP));
        byte[] ruled = drawing(1, 1, 1, 0);
        for (int x = 12; x < 90; x++) {
            paint(ruled, x, 17, 1);
            paint(ruled, x, 18, 1);
        }
        assertFalse(StemOwnedBeamlet.proves(ruled, WIDTH, HEIGHT, shaft, GAP));
    }
}
