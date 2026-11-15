// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class BackwardTertiaryHookAliasTest {
    private static final int WIDTH = 210;
    private static final int HEIGHT = 180;

    @Test
    public void requiresAnAttachedTertiaryHookWithoutItsOwnNotehead() {
        var raw = printHook(75, 114, 122);

        var filledHead = raw.clone();
        oval(filledHead, 126, 118);
        var detached = raw.clone();
        rectangle(detached, 135, 93, 139, 110, 255);
        var twoRails = raw.clone();
        rectangle(twoRails, 120, 114, 135, 122, 255);
        assertTrue(owns(raw));
        assertFalse(owns(filledHead));
        assertFalse(owns(detached));
        assertFalse(owns(twoRails));
    }

    @Test
    public void faintSeamStillSeparatesTheTwoRailsThatOwnTheHook() {
        var raw = printHook(75, 114, 122);
        for (int x = 91; x <= 134; x++) {
            int tip = 145 + Math.round((136 - x) * .1f);
            rectangle(raw, x, tip - 13, x, tip - 9, 66);
        }
        assertTrue(provesHook(raw));
        var solid = raw.clone();
        for (int x = 91; x <= 134; x++) {
            int tip = 145 + Math.round((136 - x) * .1f);
            rectangle(solid, x, tip - 13, x, tip - 9, 0);
        }
        assertFalse(provesHook(solid));
        var missing = raw.clone();
        rectangle(missing, 119, 113, 134, 122, 255);
        assertFalse(provesHook(missing));
    }

    @Test
    public void deeperTerminalHookHasNoIndependentFilledHead() {
        var raw = printHook(62, 113, 123);
        assertFalse(
                OwnedTertiaryBeamlets.ownsBackwardHookAlias(
                        raw, WIDTH, HEIGHT, 126, 118, 146, 62, 100, 83, 16));
        assertTrue(terminalAlias(raw));
        oval(raw, 126, 118);
        assertFalse(terminalAlias(raw));
    }

    private static boolean provesHook(byte[] raw) {
        return OwnedTertiaryBeamlets.provesBackwardHook(raw, WIDTH, HEIGHT, 146, 75, 100, 83, 16);
    }

    private static boolean terminalAlias(byte[] raw) {
        return OwnedTertiaryBeamlets.ownsTerminalBackwardHookAlias(
                raw, WIDTH, HEIGHT, 126, 118, 146, 62, 100, 83, 16);
    }

    private static byte[] printHook(int ownerY, int hookTop, int hookBottom) {
        byte[] raw = new byte[WIDTH * HEIGHT];
        Arrays.fill(raw, (byte) 255);
        oval(raw, 100, 83);
        oval(raw, 146, ownerY);
        rectangle(raw, 88, 83, 90, 150, 0);
        rectangle(raw, 136, ownerY, 138, 145, 0);
        for (int x = 88; x <= 138; x++) {
            int tip = 145 + Math.round((136 - x) * .1f);
            rectangle(raw, x, tip - 8, x, tip, 0);
            rectangle(raw, x, tip - 20, x, tip - 14, 0);
        }
        rectangle(raw, 120, hookTop, 138, hookBottom, 0);
        return raw;
    }

    private static boolean owns(byte[] raw) {
        return OwnedTertiaryBeamlets.ownsBackwardHookAlias(
                raw, WIDTH, HEIGHT, 126, 118, 146, 75, 100, 83, 16);
    }

    private static void oval(byte[] raw, int cx, int cy) {
        for (int y = cy - 7; y <= cy + 7; y++)
            for (int x = cx - 10; x <= cx + 10; x++)
                if (49 * (x - cx) * (x - cx) + 100 * (y - cy) * (y - cy) <= 4900)
                    raw[y * WIDTH + x] = 0;
    }

    private static void rectangle(byte[] raw, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(raw, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
