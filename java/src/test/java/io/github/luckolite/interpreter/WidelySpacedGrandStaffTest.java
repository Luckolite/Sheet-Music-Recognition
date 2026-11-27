// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class WidelySpacedGrandStaffTest {
    private static final int WIDTH = 920;
    private static final int HEIGHT = 1020;
    private static final int UPPER = 55;
    private static final int LOWER = 890;
    private static final int GAP = 12;
    private static final int[] UPPER_BARS = {65, 257, 442, 625, 856};

    @Test
    public void twoSharedInteriorRulesJoinWidelySpacedStaves() {
        assertEquals(4, measureCount(UPPER_BARS, 257, 625));
    }

    @Test
    public void oneInteriorRuleAndALeftRuleDoNotJoinWidelySpacedStaves() {
        assertEquals(8, measureCount(UPPER_BARS, 65, 257));
    }

    @Test
    public void alignedBoundariesWithoutConnectingInkStayIndependent() {
        assertEquals(8, measureCount(UPPER_BARS));
    }

    @Test
    public void tallInteriorStrokeAtOnlyOneStaffBarDoesNotConnectSystems() {
        int lowerTop = UPPER + GAP * 16;
        int[] lowerBars = {65, 310, 505, 700, 856};
        assertEquals(2, measureCountAt(lowerTop, true, lowerBars, 310, 700));
        assertEquals(1, measureCountAt(lowerTop, true, UPPER_BARS, 257));
        assertEquals(1, measureCountAt(lowerTop, true, UPPER_BARS, 65));
    }

    private static int measureCount(int[] lowerBars, int... connectors) {
        return measureCountAt(LOWER, false, lowerBars, connectors);
    }

    private static int measureCountAt(int lowerTop, boolean systems, int[] lowerBars, int... connectors) {
        byte[] labels = new byte[WIDTH * HEIGHT];
        byte[] gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        staff(labels, gray, UPPER, UPPER_BARS);
        staff(labels, gray, lowerTop, lowerBars);
        for (int x : connectors)
            for (int y = UPPER + 4 * GAP; y <= lowerTop; y++)
                gray[y * WIDTH + x] = 0;
        if (systems)
            return OmrMeasurePostProcessor.detectSystems(labels, gray, WIDTH, HEIGHT, null).systems().size();
        return OmrMeasurePostProcessor.process(labels, gray, WIDTH, HEIGHT).size();
    }

    private static void staff(byte[] labels, byte[] gray, int top, int[] bars) {
        for (int line = 0; line < 5; line++)
            for (int x = bars[0]; x <= bars[bars.length - 1]; x++) {
                int pixel = (top + line * GAP) * WIDTH + x;
                labels[pixel] = OmrMeasurePostProcessor.STAFF;
                gray[pixel] = 0;
            }
        for (int x : bars)
            for (int y = top; y <= top + 4 * GAP; y++) {
                int pixel = y * WIDTH + x;
                labels[pixel] = OmrMeasurePostProcessor.STEM_OR_REST;
                gray[pixel] = 0;
            }
    }
}
