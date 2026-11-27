// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class DisplacedWholeHeadStackTest {
    static final int WIDTH = 300, HEIGHT = 190, GAP = 16;
    static List<SeededWholeHeadRecovery.Head> read(boolean rule, boolean complete) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte)255);
        for (int i=0; i < 3; i++)
            if (i != 1 || complete) oval(gray,140, 60 + 16 * i);
        oval(gray, 119, 84);
        for (int y : new int[] {52, 68, 84, 100, 116})
            if (rule || y != 84) Arrays.fill(gray,y * WIDTH + 31, y * WIDTH + 220,(byte)0);
        return SeededWholeHeadRecovery.displacedStack(gray, WIDTH, HEIGHT, GAP, 106, 69, 154, 101);
    }

    private static void oval(byte[] gray, int cx, int cy) {
        for (int y = cy - 8; y <= cy + 8; y++)
            for (int x = cx - 13; x <= cx + 13; x++) {
                double dy = y - cy, dx = x - cx;
                double outer = dx * dx / (13.0 * 13) + dy * dy / 64;
                double shear = dx - .28 * dy;
                double inner = shear * shear / 20.25 + dy * dy / 39.69;
                if (outer <= 1 && inner >= 1) gray[y * WIDTH + x] = 0;
            }
    }

    @Test
    public void recoversEachHollowFromTheMergedNoteheadComponent() {
        var heads = read(true, true);
        assertEquals(List.of(92f, 84f, 76f, 60f), heads.stream().map(SeededWholeHeadRecovery.Head::y).toList());
        assertEquals(List.of(140f, 119f, 140f, 140f), heads.stream().map(SeededWholeHeadRecovery.Head::x).toList());
    }

    @Test
    public void detachedCompleteOvalDoesNotClaimTheRuledOpening() {
        assertTrue(read(false, true).isEmpty());
    }

    @Test
    public void missingCounterDoesNotCompleteTheChord() {
        assertTrue(read(true, false).isEmpty());
    }
}
