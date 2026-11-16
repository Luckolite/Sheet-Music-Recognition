// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class PairedFlatTailTest {
    private static final int WIDTH = 310;
    private static final int HEIGHT = 216;
    private static final float GAP = 16;
    private static final int TOP = 108;
    private static final int[] CENTERS = {46, 98, 153, 205};
    private static final int UPPER = 124;
    private static final int LOWER = 164;
    private static final int UPPER_FLAT = 126;
    private static final int LOWER_FLAT = 123;
    private static final RawStaffLineDetector.StaffLines STAFF =
            new RawStaffLineDetector.StaffLines(new int[] {TOP, 124, 140, 156, 172}, GAP);

    @Test
    public void twoFlatBodiesAndClosedRailsAreAllRequired() {
        assertTrue(new Plate(true, true, true).owns());
        assertFalse(new Plate(false, true, true).owns());
        assertFalse(new Plate(true, false, true).owns());
        assertFalse(new Plate(true, true, false).owns());
    }

    @Test
    public void twoShapesWithoutTheirIndependentAccidentalLabelsDoNotOwnTheCut() {
        var plate = new Plate(true, true, true);
        Arrays.fill(plate.labels, (byte) 0);
        assertFalse(plate.owns());
    }

    private static final class Plate {
        final byte[] gray = new byte[WIDTH * HEIGHT];
        final byte[] labels = new byte[WIDTH * HEIGHT];
        final ClosedFourDyadBeam.Head[] heads = new ClosedFourDyadBeam.Head[CENTERS.length];

        Plate(boolean upper, boolean lower, boolean continuous) {
            Arrays.fill(gray, (byte) 255);
            for (int line : STAFF.rows()) box(gray, 21, 236, line, line, (byte) 0);
            box(gray, 53, 215, 65, 70, (byte) 0);
            box(gray, 53, 215, 81, 87, (byte) 0);
            if (!continuous) box(gray, 115, 143, 79, 91, (byte) 255);
            for (int i = 0; i < CENTERS.length; i++) {
                int x = CENTERS[i];
                box(gray, x + 8, x + 10, 65, LOWER, (byte) 0);
                box(gray, x - 5, x + 10, UPPER - 4, UPPER + 4, (byte) 0);
                box(gray, x - 5, x + 10, LOWER - 4, LOWER + 4, (byte) 0);
                heads[i] = new ClosedFourDyadBeam.Head(x, UPPER, LOWER);
            }
            if (upper) flat(UPPER_FLAT, UPPER);
            if (lower) flat(LOWER_FLAT, LOWER);
        }

        boolean owns() {
            return BeamedFlatBarline.ownsPairedFlats(
                    labels, gray, WIDTH, HEIGHT, heads, 118, 136, STAFF);
        }

        void flat(int x, int head) {
            box(gray, x, x + 1, head - 24, head + 12, (byte) 0);
            box(gray, x + 7, x + 13, head - 9, head + 2, (byte) 0);
            box(gray, x + 4, x + 9, head + 6, head + 8, (byte) 0);
            box(labels, x, x + 1, head - 24, head + 12, (byte) OmrMeasurePostProcessor.CLEF_OR_KEY);
        }
    }

    private static void box(byte[] plane, int l, int r, int t, int b, byte ink) {
        for (int y = t; y <= b; y++) Arrays.fill(plane, y * WIDTH + l, y * WIDTH + r + 1, ink);
    }
}
