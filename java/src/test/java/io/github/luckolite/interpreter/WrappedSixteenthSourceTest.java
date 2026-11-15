// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class WrappedSixteenthSourceTest {
    private static final int WIDTH = 440;
    private static final int HEIGHT = 320;
    private static final float GAP = 16;
    private static final int[] CENTERS = {104, 130, 156, 182, 212, 238, 264, 290};
    private static final MeasureRegion REGION =
            new MeasureRegion(85f / WIDTH, 315f / WIDTH, 68f / HEIGHT, 199f / HEIGHT);

    private record Page(byte[] gray, byte[] labels) {}

    @Test
    public void provesTwoIndependentFourDyadGroups() {
        var slots = read(page());
        assertEquals(8, slots.size());
        for (int i = 0; i < CENTERS.length; i++) {
            assertEquals(CENTERS[i], slots.get(i).x(), 1.5f);
        }
    }

    @Test
    public void missingRailExtraBridgeAndMissingHeadAbstain() {
        var erased = page();
        paint(erased.gray(), 126, 141, 150, 159, 255);
        assertTrue(read(erased).isEmpty());

        var connected = page();
        paint(connected.gray(), 172, 204, 151, 157, 0);
        paint(connected.gray(), 172, 204, 165, 171, 0);
        assertTrue(read(connected).isEmpty());

        var missing = page();
        paint(missing.labels(), 196, 226, 98, 120, 0);
        assertTrue(read(missing).isEmpty());
    }

    private static List<WrappedSixteenthSource.Slot> read(Page page) {
        return WrappedSixteenthSource.half(page.labels(), page.gray(), WIDTH, HEIGHT, REGION, GAP);
    }

    private static Page page() {
        byte[] labels = new byte[WIDTH * HEIGHT];
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 84; y <= 148; y += 16) paint(gray, 22, 320, y, y + 1, 0);
        for (int x : CENTERS) {
            head(labels, gray, x, 90);
            head(labels, gray, x, 106);
            paint(gray, x - 10, x - 8, 92, 171, 0);
        }
        for (int i : new int[] {0, 4}) {
            paint(gray, CENTERS[i] - 10, CENTERS[i + 3] - 8, 151, 157, 0);
            paint(gray, CENTERS[i] - 10, CENTERS[i + 3] - 8, 165, 171, 0);
        }
        return new Page(gray, labels);
    }

    private static void head(byte[] labels, byte[] gray, int x, int y) {
        for (int yy = -9; yy <= 9; yy++)
            for (int xx = -12; xx <= 12; xx++) {
                if (xx * xx / 144f + yy * yy / 81f > 1) continue;
                int p = (y + yy) * WIDTH + x + xx;
                labels[p] = OmrMeasurePostProcessor.NOTEHEAD;
                gray[p] = 0;
            }
    }

    private static void paint(byte[] plane, int l, int r, int t, int b, int value) {
        for (int y = t; y <= b; y++) Arrays.fill(plane, y * WIDTH + l, y * WIDTH + r + 1, (byte) value);
    }
}
