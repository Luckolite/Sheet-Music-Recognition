// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original synthetic rules: no source score pixels or coordinates. */
public class ShortFinalStaffTest {
    private static final int W = 1200, H = 600, LEFT = 70, RIGHT = 270;
    private static final int[] ROWS = {100, 116, 132, 148, 164};

    private byte[] page(boolean doubleBar) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int row : ROWS)
            for (int x = LEFT; x <= RIGHT; x++) gray[row * W + x] = 0;
        for (int y = ROWS[0]; y <= ROWS[4]; y++) {
            gray[y * W + RIGHT] = 0;
            if (doubleBar) gray[y * W + RIGHT - 7] = 0;
        }
        return gray;
    }

    private boolean proved(byte[] gray) {
        return ShortFinalStaff.proved(null, gray, W, H, ROWS, 16, LEFT, RIGHT, 0);
    }

    @Test public void shortFinalRulesPassWithoutPageWideThreshold() {
        assertTrue(proved(page(true)));
    }

    @Test public void aSingleBarDoesNotRelaxTheWidthGuard() {
        assertFalse(proved(page(false)));
    }

    @Test public void singleClosingBarNeedsAnOpeningClefAndDistinctPrintedAttacks() {
        byte[] gray = page(false), labels = new byte[gray.length];
        for (int row : ROWS)
            for (int x = LEFT; x <= RIGHT; x++) labels[row * W + x] = 4;
        for (int y = 100; y <= 162; y++)
            for (int x = 78; x <= 83; x++) {
                gray[y * W + x] = 0;
                labels[y * W + x] = OmrMeasurePostProcessor.CLEF_OR_KEY;
            }
        for (int y = 139; y <= 146; y++)
            for (int x = 146; x <= 154; x++) {
                gray[y * W + x] = 0;
                labels[y * W + x] = OmrMeasurePostProcessor.NOTEHEAD;
                labels[y * W + x + 40] = OmrMeasurePostProcessor.NOTEHEAD;
            }
        assertFalse(ShortFinalStaff.proved(labels, gray, W, H, ROWS, 16, LEFT, RIGHT, 0));
        for (int y = 139; y <= 146; y++)
            for (int x = 186; x <= 194; x++) gray[y * W + x] = 0;
        assertTrue(ShortFinalStaff.proved(labels, gray, W, H, ROWS, 16, LEFT, RIGHT, 0));
    }

    @Test public void labelsAloneCannotInventPrintedRules() {
        byte[] gray = page(true);
        Arrays.fill(gray, 132 * W + LEFT, 132 * W + RIGHT, (byte) 255);
        assertFalse(proved(gray));
        assertFalse(proved(null));
    }

    @Test public void shortStaffReachesMeasureGeometry() {
        byte[] gray = page(true), labels = new byte[gray.length];
        for (int row : ROWS)
            for (int x = LEFT; x <= RIGHT; x++) labels[row * W + x] = 4;
        assertFalse(OmrMeasurePostProcessor.process(labels, gray, W, H).isEmpty());
        assertTrue(OmrMeasurePostProcessor.process(labels, null, W, H).isEmpty());
    }

    @Test public void textLikeBrokenRulesRemainRejected() {
        byte[] gray = page(true);
        for (int row : ROWS)
            for (int x = LEFT; x < RIGHT; x++) if (x % 12 < 6) gray[row * W + x] = (byte) 255;
        assertFalse(proved(gray));
    }
}
