// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClosedMixedHarmonicQuarterInkTest {
    private static final int W = 130, H = 140;
    private static final float GAP = 16;

    @Test
    public void bothTaperedShouldersMustExtendFromTheLedger() {
        var gray = paper();
        var labels = new byte[gray.length];
        int x = 58, y = 66;
        for (int offset = -9; offset <= 9; offset++) {
            int span = 9 - Math.abs(offset);
            row(gray, x - span, x + span, y + offset, 0);
            row(labels, x - span, x + span, y + offset, OmrMeasurePostProcessor.SYMBOL);
        }
        for (int delta = -2; delta <= 2; delta++) row(gray, x - 19, x + 19, y + delta, 0);
        for (int j = y + 2; j <= y + 22; j++) row(gray, x - 8, x - 7, j, 0);
        assertTrue(LedgerCrossedHarmonicDiamond.proved(labels, gray, W, H, x, y, GAP, x - 7, -1));

        var noUpper = gray.clone();
        for (int j = y - 10; j <= y - 6; j++) row(noUpper, x - 12, x + 12, j, 255);
        assertFalse(LedgerCrossedHarmonicDiamond.proved(labels, noUpper, W, H, x, y, GAP, x - 7, -1));
        var noLower = gray.clone();
        for (int j = y + 6; j <= y + 10; j++) row(noLower, x - 5, x + 11, j, 255);
        assertFalse(LedgerCrossedHarmonicDiamond.proved(labels, noLower, W, H, x, y, GAP, x - 7, -1));
        assertFalse(LedgerCrossedHarmonicDiamond.proved(new byte[gray.length], gray, W, H,
                x, y, GAP, x - 7, -1));
    }

    @Test
    public void theTornZeroStillNeedsConvergingTopAndJoinedBottom() {
        var gray = paper();
        int cx = 61, top = 33;
        row(gray, cx - 4, cx - 4, top, 0);
        row(gray, cx + 4, cx + 4, top, 0);
        for (int dy = 1; dy <= 14; dy++) {
            int outside = dy <= 3 ? 4 + dy : dy <= 12 ? 7 : 19 - dy;
            row(gray, cx - outside, cx - 3 - (dy == 14 ? 0 : 1), top + dy, 0);
            row(gray, cx + 3 + (dy == 14 ? 0 : 1), cx + outside, top + dy, 0);
        }
        row(gray, cx - 4, cx + 4, top + 15, 0);
        row(gray, cx - 3, cx + 3, top + 16, 0);
        row(gray, cx - 1, cx + 1, top + 17, 0);
        assertNotNull(DetachedHarmonicZero.above(gray, W, H, cx, 65, GAP, true));
        assertNull(DetachedHarmonicZero.above(gray, W, H, cx, 65, GAP, false));
        var missingJoin = gray.clone();
        for (int dy = 15; dy <= 17; dy++) row(missingJoin, cx - 2, cx + 2, top + dy, 255);
        assertNull(DetachedHarmonicZero.above(missingJoin, W, H, cx, 65, GAP, true));
    }

    private static byte[] paper() {
        var page = new byte[W * H];
        Arrays.fill(page, (byte)255);
        return page;
    }

    private static void row(byte[] page, int from, int to, int y, int value) {
        Arrays.fill(page, y * W + from, y * W + to + 1, (byte)value);
    }
}
