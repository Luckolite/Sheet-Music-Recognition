// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class UpperLedgerSpaceCentroidTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 230;
    private static final int X = 70;
    private static final float GAP = 16;

    @Test
    public void ledgerClippedOvalStillBelongsToTheSpaceAboveTheTwoRules() {
        var gray = paper();
        rule(gray, X - 14, X + 14, 91);
        rule(gray, X - 14, X + 14, 106);
        assertTrue(preserves(gray, 82, 84));
        assertFalse(preserves(gray, 90, 91));
    }

    @Test
    public void isolatedBarAndContinuingBeamDoNotSupplyTwoLedgerRules() {
        var one = paper();
        rule(one, X - 14, X + 14, 91);
        assertFalse(preserves(one, 82, 84));

        var beam = one.clone();
        rule(beam, X - 34, X + 35, 106);
        assertFalse(preserves(beam, 82, 84));
    }

    @Test
    public void competingStripeThroughTheHeadVetoesTheSpace() {
        var gray = paper();
        rule(gray, X - 14, X + 14, 91);
        rule(gray, X - 14, X + 14, 106);
        rule(gray, X - 14, X + 14, 85);
        assertFalse(preserves(gray, 82, 84));
    }

    private static boolean preserves(byte[] gray, float center, float opened) {
        return OmrScoreInterpreter.preservesUpperLedgerSpace(
                gray, WIDTH, HEIGHT, X, center, opened, 186, GAP, 12);
    }

    private static byte[] paper() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        return gray;
    }

    private static void rule(byte[] gray, int left, int right, int y) {
        for (int row = y - 1; row <= y + 1; row++) {
            Arrays.fill(gray, row * WIDTH + left, row * WIDTH + right + 1, (byte) 0);
        }
    }
}
