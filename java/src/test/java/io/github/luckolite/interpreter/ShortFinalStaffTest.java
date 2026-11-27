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
        offsetTail(gray, ROWS[0], 1);
        assertFalse(ShortFinalStaff.proved(labels, gray, W, H, ROWS, 16, LEFT, RIGHT, 0));
    }

    private static void offsetTail(byte[] gray, int row, int shift) {
        for (int x = 225; x < RIGHT - 16; x++) {
            gray[row * W + x] = (byte) 255;
            gray[(row + shift) * W + x] = 0;
        }
    }

    @Test public void oneRoundedRailKeepsThePrintedDoubleClose() {
        byte[] gray = page(true);
        offsetTail(gray, ROWS[0], 1);
        assertTrue(proved(gray));
    }

    @Test public void multipleRoundedRailsOrTwoPixelsStillFail() {
        byte[] gray = page(true);
        offsetTail(gray, ROWS[0], 1);
        offsetTail(gray, ROWS[1], 1);
        assertFalse(proved(gray));

        gray = page(true);
        offsetTail(gray, ROWS[0], 2);
        assertFalse(proved(gray));
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

    private byte[] countedRest(boolean doubleBar, boolean count, boolean central) {
        byte[] gray = page(doubleBar);
        int left = 178, right = 235, middle = ROWS[2] + (central ? 0 : 16);
        for (int y = middle - 6; y <= middle + 6; y++)
            Arrays.fill(gray, y * W + left, y * W + right + 1, (byte) 0);
        if (count) {
            int x = (left + right) / 2;
            for (int y = 65; y <= 69; y++)
                Arrays.fill(gray, y * W + x - 8, y * W + x + 9, (byte) 0);
            for (int y = 70; y <= 86; y++) {
                int d = y - 70;
                Arrays.fill(gray, y * W + x + 7 - d, y * W + x + 12 - d, (byte) 0);
            }
            for (int y = 86; y <= 89; y++)
                Arrays.fill(gray, y * W + x - 9, y * W + x + 10, (byte) 0);
        }
        return gray;
    }

    @Test public void countedRestMayObscureTheMiddleRuleOfAShortSystem() {
        assertTrue(proved(countedRest(true, true, true)));
        assertFalse(proved(countedRest(true, false, true)));
        assertFalse(proved(countedRest(false, true, true)));
        assertFalse(proved(countedRest(true, true, false)));
    }

    @Test public void aCountedRestDoesNotExcuseMissingOutsideRules() {
        byte[] gray = countedRest(true, true, true);
        Arrays.fill(gray, ROWS[0] * W + LEFT, ROWS[0] * W + RIGHT, (byte) 255);
        assertFalse(proved(gray));
    }
    @Test public void backgroundInkLabelsDoNotCropThePrintedTerminalAttack() {
        byte[] gray = page(true), labels = new byte[gray.length];
        for (int row : ROWS)
            Arrays.fill(labels, row * W + LEFT, row * W + RIGHT,
                    OmrMeasurePostProcessor.STAFF);
        // Artwork below the staff falls into the wide header ledger search.
        for (int y = 186; y <= 272; y++)
            Arrays.fill(labels, y * W + 151, y * W + 210,
                    OmrMeasurePostProcessor.CLEF_OR_KEY);
        for (int y = 168; y <= 181; y++)
            for (int x = 156; x <= 183; x++)
                if ((x - 169) * (x - 169) / 196f + (y - 174) * (y - 174) / 49f <= 1) {
                    labels[y * W + x] = OmrMeasurePostProcessor.NOTEHEAD;
                    if (Math.abs(x - 169) > 5 || Math.abs(y - 174) > 3) gray[y * W + x] = 0;
                }
        var measures = OmrMeasurePostProcessor.process(labels, gray, W, H);
        assertEquals(1, measures.size());
        assertTrue(measures.toString(), measures.get(0).left() * W < 158);
    }
}
