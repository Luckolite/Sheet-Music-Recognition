// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import org.junit.Test;

public final class StaffOverlaidBeamFingeringTest {
    private static final int WIDTH = 260, HEIGHT = 126, X = 118, BOTTOM = 80, GAP = 16;
    private final byte[] ink = new byte[WIDTH * HEIGHT];

    public StaffOverlaidBeamFingeringTest() {
        Arrays.fill(ink, (byte) 255);
        fill(50, 213, 63, 65);
        fill(50, 213, 79, 81);
    }

    @Test
    public void serifAndFootIdentifyOneWithoutAnOcrWord() {
        one();
        assertEquals(1, digit(-1));
    }

    @Test
    public void staffRulesDoNotValidateAnOcrHint() {
        assertEquals(-1, digit(2));
        assertEquals(-1, digit(-1));
    }

    @Test
    public void straightMarkWithNoFootIsNotAssignedToTheNote() {
        fill(114, 119, 59, 77);
        assertEquals(-1, digit(-1));
    }

    private void one() {
        fill(113, 118, 59, 60);
        fill(112, 119, 61, 62);
        fill(114, 119, 64, 74);
        fill(112, 121, 75, 77);
    }

    private int digit(int hint) {
        return StaffOverlaidBeamFingering.digit(ink, WIDTH, HEIGHT, X, BOTTOM, GAP, hint);
    }

    private void fill(int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
