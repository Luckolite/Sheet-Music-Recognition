// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class JoinedTertiaryHookTest {
    private static final int WIDTH = 210;
    private static final int HEIGHT = 180;

    @Test
    public void detectsAnAttachedShortThirdHookAlongsideSeparatedSharedRails() {
        var source = pairedRails();
        ink(source, 125, 115, 138, 126, 0);
        assertTrue(ownedHook(source));
        assertFalse(ownedHook(pairedRails()));

        var detached = source.clone();
        ink(detached, 134, 114, 135, 124, 255);
        assertFalse(ownedHook(detached));

        var extended = source.clone();
        ink(extended, 103, 115, 125, 125, 0);
        assertFalse(ownedHook(extended));
    }

    @Test
    public void retainsTheReversedHeadWhenItLacksAnAttachedTertiaryStroke() {
        var source = pairedRails();
        ink(source, 120, 113, 138, 123, 0);
        assertTrue(compactAlias(source));
        assertFalse(compactAlias(pairedRails()));

        var detached = source.clone();
        ink(detached, 135, 102, 140, 108, 255);
        assertFalse(compactAlias(detached));

        var separateHead = source.clone();
        oval(separateHead, 129, 118);
        assertFalse(compactAlias(separateHead));
    }

    private static boolean ownedHook(byte[] source) {
        return OwnedTertiaryBeamlets.provesJoinedBackwardHook(
                source, WIDTH, HEIGHT, 146, 78, 100, 85, 16);
    }

    private static boolean compactAlias(byte[] source) {
        return OwnedTertiaryBeamlets.ownsCompactBackwardHookAlias(
                source, WIDTH, HEIGHT, 129, 118, 146, 78, 100, 85, 16);
    }

    private static byte[] pairedRails() {
        var source = new byte[WIDTH * HEIGHT];
        Arrays.fill(source, (byte) 255);
        oval(source, 100, 85);
        oval(source, 146, 78);
        ink(source, 88, 85, 90, 145, 0);
        ink(source, 136, 78, 138, 145, 0);
        ink(source, 88, 138, 138, 145, 0);
        ink(source, 88, 125, 138, 131, 0);
        return source;
    }

    private static void oval(byte[] source, int cx, int cy) {
        for (int y = cy - 7; y <= cy + 7; y++) {
            for (int x = cx - 10; x <= cx + 10; x++) {
                if (49 * (x - cx) * (x - cx) + 100 * (y - cy) * (y - cy) <= 4900)
                    source[y * WIDTH + x] = 0;
            }
        }
    }

    private static void ink(byte[] source, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(source, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
