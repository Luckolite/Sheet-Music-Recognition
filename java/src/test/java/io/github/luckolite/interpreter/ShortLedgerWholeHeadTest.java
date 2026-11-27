// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Synthetic whole heads crossed by ledger and staff rules of different lengths. */
public class ShortLedgerWholeHeadTest {
    private static final int WIDTH = 120, HEIGHT = 96;
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    private void paint(int top, int bottom, int left, int right, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }

    private void oval(boolean upperOpening, boolean lowerOpening) {
        Arrays.fill(gray, (byte) 255);
        for (int y = 36; y <= 60; y++)
            for (int x = 46; x <= 74; x++) {
                double dx = (x - 60) / 14.0, dy = (y - 48) / 12.0;
                if (dx * dx + dy * dy <= 1) gray[y * WIDTH + x] = 30;
            }
        if (upperOpening) paint(44, 46, 57, 63, 190);
        if (lowerOpening) paint(50, 53, 56, 64, 240);
    }

    private boolean open() {
        return WholeHeadPocketEvidence.shortLedgerPocket(
                gray, WIDTH, HEIGHT, 46, 36, 74, 60, 60, 48, 18);
    }

    @Test
    public void shortLedgerLeavesTwoProvedOpenings() {
        oval(true, true);
        paint(47, 49, 40, 80, 65);
        assertTrue(open());
    }

    @Test
    public void longStaffLineCannotTriggerTheShortLedgerPath() {
        oval(true, true);
        paint(47, 49, 6, 111, 65);
        assertFalse(open());
    }

    @Test
    public void aFilledHeadAndARuleRemainOpaque() {
        oval(false, false);
        paint(47, 49, 40, 80, 65);
        assertFalse(open());
    }

    @Test
    public void singlePocketCannotTriggerTheSplitOpeningPath() {
        oval(false, true);
        paint(47, 49, 40, 80, 65);
        assertFalse(open());
    }
}
