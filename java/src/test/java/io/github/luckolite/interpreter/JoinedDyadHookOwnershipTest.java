// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class JoinedDyadHookOwnershipTest {
    private static final int WIDTH = 210, HEIGHT = 180, GAP = 16;
    private static final int PEER_X = 100, PEER_LOWER = 82, PEER_UPPER = 26;
    private static final int OWNER_X = 146, OWNER_LOWER = 78, OWNER_UPPER = 22;

    @Test
    public void shortThirdRailBelongsToBothHeadsOnTheClosingShaft() {
        var page = shafts();
        assertFalse(closingHook(page));
        ink(page, 125, 115, 138, 125, 0);
        assertTrue(closingHook(page));

        var blankBetweenHeads = page.clone();
        ink(blankBetweenHeads, 135, 49, 139, 55, 255);
        assertFalse(closingHook(blankBetweenHeads));

        var throughRail = page.clone();
        ink(throughRail, 90, 115, 125, 125, 0);
        assertFalse(closingHook(throughRail));
    }

    @Test
    public void reversedMaskMustBorrowTheSharedShaftUpToTheRealUpperHead() {
        var page = shafts();
        assertFalse(reversedHook(page));
        ink(page, 120, 113, 138, 123, 0);
        assertTrue(reversedHook(page));

        ink(page, 135, 49, 139, 55, 255);
        assertFalse(reversedHook(page));
    }

    private static boolean closingHook(byte[] page) {
        return OwnedTertiaryBeamlets.provesJoinedClosingDyadHook(
                page,
                WIDTH,
                HEIGHT,
                OWNER_X,
                OWNER_LOWER,
                OWNER_X,
                OWNER_UPPER,
                PEER_X,
                PEER_UPPER,
                PEER_X,
                PEER_LOWER,
                GAP,
                new PrintedBeamShafts.Budget(100_000));
    }

    private static boolean reversedHook(byte[] page) {
        return OwnedTertiaryBeamlets.ownsReversedOctaveHook(
                page,
                WIDTH,
                HEIGHT,
                129,
                118,
                OWNER_X,
                OWNER_LOWER,
                OWNER_X,
                OWNER_UPPER,
                PEER_X,
                PEER_LOWER,
                GAP,
                new PrintedBeamShafts.Budget(100_000));
    }

    private static byte[] shafts() {
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        for (int y : new int[] {PEER_UPPER, PEER_LOWER}) head(page, PEER_X, y);
        for (int y : new int[] {OWNER_UPPER, OWNER_LOWER}) head(page, OWNER_X, y);
        ink(page, 88, PEER_UPPER, 90, 145, 0);
        ink(page, 135, OWNER_UPPER, 137, 145, 0);
        ink(page, 88, 138, 138, 145, 0);
        ink(page, 88, 125, 138, 131, 0);
        return page;
    }

    private static void head(byte[] page, int cx, int cy) {
        for (int dy = -7; dy <= 7; dy++) {
            for (int dx = -10; dx <= 10; dx++) {
                if (49 * dx * dx + 100 * dy * dy <= 4_900) page[(cy + dy) * WIDTH + cx + dx] = 0;
            }
        }
    }

    private static void ink(byte[] page, int left, int top, int right, int bottom, int shade) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) shade);
    }
}
