// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import org.junit.Test;

public final class WideMeterEmptySignatureTest {
    private static final int WIDTH = 240, HEIGHT = 155;

    @Test
    public void wideStackedNumeralsWithTheBlankSlotProveAnEmptyKey() {
        assertTrue(read(false, true, true));
    }

    @Test
    public void unlabelledMeterLikeInkMissingCountersOrKeyInkAbstains() {
        assertFalse(read(true, true, true));
        assertFalse(read(false, false, true));
        assertFalse(read(false, true, false));
    }

    private static boolean read(boolean accidental, boolean counters, boolean symbols) {
        byte[] labels = new byte[WIDTH * HEIGHT], gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        for (int y = 37; y <= 101; y += 16)
            Arrays.fill(gray, y * WIDTH + 30, y * WIDTH + 190, (byte) 0);
        byte label = symbols ? OmrMeasurePostProcessor.SYMBOL : 0;
        // An original wide upper pair and a centered enclosed lower numeral.
        black(gray, labels, 76, 45, 87, 66, label);
        black(gray, labels, 91, 41, 117, 47, label);
        black(gray, labels, 106, 46, 117, 60, label);
        black(gray, labels, 92, 57, 117, 66, label);
        black(gray, labels, 87, 71, 108, 76, label);
        black(gray, labels, 87, 83, 108, 88, label);
        black(gray, labels, 87, 95, 108, 100, label);
        black(gray, labels, 87, 75, 92, 96, label);
        black(gray, labels, 103, 75, 108, 96, label);
        if (!counters) black(gray, labels, 91, 75, 104, 96, label);
        if (accidental)
            black(gray, labels, 67, 47, 70, 68, OmrMeasurePostProcessor.CLEF_OR_KEY);
        return ExplicitEmptySignature.matchesWideMeter(labels, gray, WIDTH, HEIGHT, 58, 151, 101, 16);
    }

    private static void black(byte[] gray, byte[] labels,
            int left, int top, int right, int bottom, byte label) {
        for (int y = top; y < bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right, (byte) 0);
            Arrays.fill(labels, y * WIDTH + left, y * WIDTH + right, label);
        }
    }
}
