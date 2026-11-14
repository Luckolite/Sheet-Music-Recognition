// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class MonophonicTertiaryBeamTest {
    private static final int WIDTH = 370;
    private static final int HEIGHT = 160;
    private static final int GAP = 16;

    @Test
    public void recoversAnOwnedHookBeforeASeparateOpposingGroup() {
        assertEquals(List.of(2), read(true, true));
    }

    @Test
    public void anOrdinaryBeamAndAnIncompleteNeighborCannotAdmitTheRule() {
        assertEquals(List.of(), read(false, true));
        assertEquals(List.of(), read(true, false));
    }

    private static List<Integer> read(boolean attach, boolean completeFollowingGroup) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 45; y <= 109; y += GAP) ink(gray, 15, y, 355, y);
        int[] x = {65, 113, 154, 188, 233};
        int[] y = {101, 93, 85, 77, 69};
        int[] beams = {1, 2, 2, 2, 3};
        int[] dots = {0, 1, 0, 1, 0};
        int p0 = x[0] + 9, p1 = x[1] + 9, p2 = x[2] + 9;
        ink(gray, p0, 28, p0 + 2, y[0]);
        ink(gray, p1, 28, p1 + 2, y[1]);
        ink(gray, p2, 28, p2 + 2, y[2]);
        ink(gray, p0, 28, p2 + 2, 34);
        ink(gray, p1, 42, p2 + 2, 48);
        if (attach) ink(gray, p2 - 13, 54, p2 + 2, 63);
        ink(gray, x[3] - 10, y[3], x[3] - 8, 123);
        ink(gray, x[4] - 10, y[4], x[4] - 8, 123);
        ink(gray, x[3] - 10, 116, x[4] - 8, 123);
        var heads = new ArrayList<OwnedTertiaryBeamlets.Head>();
        for (int i = 0; i < (completeFollowingGroup ? 5 : 4); i++) {
            oval(gray, x[i], y[i]);
            var note = new ScoreNoteEvent(0, x[i] / (float) WIDTH, (109 - y[i]) / 8, 0, 1,
                    y[i] / (float) HEIGHT, false, dots[i], beams[i],
                    ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0).withStemDirection(i < 3 ? 1 : -1);
            heads.add(new OwnedTertiaryBeamlets.Head(i, note, x[i], y[i], GAP));
        }
        return OwnedTertiaryBeamlets.recoverMonophonic(gray, WIDTH, HEIGHT, heads);
    }

    private static void oval(byte[] gray, int cx, int cy) {
        for (int y = -6; y <= 6; y++)
            for (int x = -10; x <= 10; x++)
                if (x * x + 2 * y * y <= 100) gray[(cy + y) * WIDTH + cx + x] = 0;
    }

    private static void ink(byte[] gray, int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
