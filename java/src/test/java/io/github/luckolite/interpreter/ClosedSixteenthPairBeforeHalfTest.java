// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class ClosedSixteenthPairBeforeHalfTest {
    private static final int WIDTH = 250;
    private static final int HEIGHT = 200;
    private static final int TOP = 60;
    private static final int GAP = 16;
    private static final int BOTTOM = TOP + 4 * GAP;
    private static final int RULE = 120;

    @Test
    public void requiresOneIndependentRuleBoundedByTheStaff() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int line = TOP; line <= BOTTOM; line += GAP)
            Arrays.fill(gray, line * WIDTH + 24, line * WIDTH + WIDTH - 24, (byte) 0);

        assertFalse(ownsSeparator(gray));

        for (int y = TOP; y <= BOTTOM; y++)
            Arrays.fill(gray, y * WIDTH + RULE - 1, y * WIDTH + RULE + 3, (byte) 0);
        assertTrue(ownsSeparator(gray));

        byte[] twoRules = gray.clone();
        for (int y = TOP; y <= BOTTOM; y++)
            Arrays.fill(twoRules, y * WIDTH + RULE + 5, y * WIDTH + RULE + 8, (byte) 0);
        assertFalse(ownsSeparator(twoRules));

        for (int y = TOP - 18; y < TOP; y++)
            Arrays.fill(gray, y * WIDTH + RULE - 1, y * WIDTH + RULE + 3, (byte) 0);
        assertFalse(ownsSeparator(gray));
    }

    private static boolean ownsSeparator(byte[] gray) {
        var staff = new PlayingTechniqueDetector.Staff(TOP, BOTTOM, GAP, 0, 1);
        return ClosedSixteenthPairBeforeHalf.ownedSeparator(gray, WIDTH, HEIGHT, staff, RULE);
    }
}
