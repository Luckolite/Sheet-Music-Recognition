// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class FingeringOneAugmentationTest {
    private static final int WIDTH = 125;
    private static final int HEIGHT = 92;
    private static final int RULE = 58;

    @Test
    public void aDigitAcrossTheRuleBelongsToTheFollowingHead() {
        assertTrue(proves(printedFingering()));
        assertFalse(proves(staff()));
    }

    @Test
    public void anIndependentDotCanCoexistWithTheFingering() {
        var ink = printedFingering();
        rectangle(ink, 58, 63, 49, 54);
        assertFalse(proves(ink));
    }

    @Test
    public void aDotAttachedToTheStaffRuleStillVetoesRemoval() {
        var ink = printedFingering();
        rectangle(ink, 56, 62, 54, 58);
        assertFalse(proves(ink));
    }

    private static boolean proves(byte[] ink) {
        return FingeringOneAugmentation.provesDigitAndClearance(ink, WIDTH, HEIGHT, 37, 51, 74, 34, RULE, 16);
    }

    private static byte[] staff() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte)255);
        rectangle(ink, 12, 113, RULE - 1, RULE + 1);
        rectangle(ink, 12, 113, RULE - 17, RULE - 15);
        return ink;
    }

    private static byte[] printedFingering() {
        var ink = staff();
        rectangle(ink, 72, 77, 49, 50);
        rectangle(ink, 70, 77, 51, 53);
        rectangle(ink, 68, 77, 54, 55);
        rectangle(ink, 73, 77, 56, 65);
        rectangle(ink, 71, 79, 66, 68);
        return ink;
    }

    private static void rectangle(byte[] ink, int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte)0);
    }
}
