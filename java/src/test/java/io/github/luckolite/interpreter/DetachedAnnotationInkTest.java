// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DetachedAnnotationInkTest {
    private static final int W = 360, H = 240, G = 16;
    private final byte[] gray = new byte[W * H];

    public DetachedAnnotationInkTest() {
        Arrays.fill(gray, (byte) 255);
    }

    private void rect(int l, int r, int t, int b) {
        for (int y = t; y <= b; y++) for (int x = l; x <= r; x++) gray[y * W + x] = 0;
    }

    private void accent() {
        for (int x = 160; x <= 184; x++)
            for (int side : new int[] {-1, 1}) {
                int cy = Math.round(110 + side * 7 * (184 - x) / 24f);
                rect(x, x, cy - 1, cy + 1);
            }
    }

    private boolean accentTip() {
        return DetachedAnnotationInk.accent(gray, W, H, 180, 107, 185, 113, G);
    }

    private void bow() {
        rect(160, 164, 60, 110);
        rect(196, 200, 60, 110);
        rect(160, 200, 60, 64);
    }

    private boolean bowTip() {
        return DetachedAnnotationInk.downBow(gray, W, H, 196, 105, 201, 112, G);
    }

    @Test
    public void accentTipIsNotAnotherNote() {
        accent();
        assertTrue(accentTip());
    }

    @Test
    public void filledOvalIsNotAccent() {
        for (int y = 102; y <= 118; y++)
            for (int x = 160; x <= 184; x++)
                if ((x - 172) * (x - 172) / 144d + (y - 110) * (y - 110) / 64d <= 1)
                    gray[y * W + x] = 0;
        assertFalse(accentTip());
    }

    @Test
    public void oneDiagonalStrokeIsNotAccent() {
        for (int x = 160; x <= 184; x++) rect(x, x, 103 + (x - 160) / 3, 105 + (x - 160) / 3);
        assertFalse(accentTip());
    }

    @Test
    public void downBowEndpointIsNotAnotherNote() {
        bow();
        assertTrue(bowTip());
    }

    @Test
    public void thinSlurTouchingBowDoesNotExtendItsLeg() {
        bow();
        rect(130, 240, 109, 109);
        assertTrue(bowTip());
    }

    @Test
    public void oneStemAndCapAreNotDownBow() {
        rect(196, 200, 60, 110);
        rect(160, 200, 60, 64);
        assertFalse(bowTip());
    }

    @Test
    public void capIsNotAMistakenBottomEndpoint() {
        bow();
        assertFalse(DetachedAnnotationInk.downBow(gray, W, H, 176, 60, 181, 64, G));
    }

    @Test
    public void filledBoxIsNotDownBow() {
        rect(160, 200, 60, 110);
        assertFalse(bowTip());
    }

    @Test
    public void invalidImageIsRejected() {
        assertFalse(DetachedAnnotationInk.accent(null, W, H, 180, 107, 185, 113, G));
    }

    @Test
    public void sourceImageIsNotChanged() {
        bow();
        var copy = gray.clone();
        bowTip();
        assertArrayEquals(copy, gray);
    }

    @Test
    public void lateThresholdComponentsUseOnlyTheirNewQueuePrefix() {
        for (int ink : new int[] {81, 111, 141, 181, 205}) {
            Arrays.fill(gray, (byte) 255);
            accent();
            for (int i = 0; i < gray.length; i++) if (gray[i] == 0) gray[i] = (byte) ink;
            // This larger early dark component is disjoint from the pale accent tip.
            rect(185, 200, 80, 107);
            byte[] before = gray.clone();
            assertTrue(accentTip());
            assertTrue(accentTip());
            assertArrayEquals(before, gray);

            Arrays.fill(gray, (byte) 255);
            bow();
            for (int i = 0; i < gray.length; i++) if (gray[i] == 0) gray[i] = (byte) ink;
            // Its one surviving thick center is disjoint from the pale bow leg.
            rect(200, 202, 111, 113);
            before = gray.clone();
            assertTrue(bowTip());
            assertTrue(bowTip());
            assertArrayEquals(before, gray);
        }
    }

    @Test
    public void rejectedAndChangedRasterCannotReuseAnEarlierComponent() {
        bow();
        byte[] before = gray.clone();
        assertTrue(bowTip());
        assertArrayEquals(before, gray);
        Arrays.fill(gray, (byte) 255);
        rect(160, 200, 60, 110);
        before = gray.clone();
        assertFalse(bowTip());
        assertFalse(bowTip());
        assertArrayEquals(before, gray);
        Arrays.fill(gray, (byte) 255);
        accent();
        before = gray.clone();
        assertTrue(accentTip());
        assertArrayEquals(before, gray);
        Arrays.fill(gray, (byte) 255);
        before = gray.clone();
        assertFalse(accentTip());
        assertFalse(bowTip());
        assertArrayEquals(before, gray);
    }
    @Test
    public void wholePrintedDownBowOwnsRaisedRestCropAboveTheRule() {
        rect(160, 177, 60, 65);
        rect(160, 162, 65, 79);
        rect(175, 177, 65, 79);
        rect(110, 220, 87, 89);
        assertTrue(DetachedAnnotationInk.downBowRest(gray, W, H, 160, 60, 177, 89, G));
        assertFalse(DetachedAnnotationInk.downBowRest(gray, W, H, 165, 60, 185, 89, G));
    }

    @Test
    public void raisedRestBulbAndDiagonalDoNotMakeTwoBowLegs() {
        rect(160, 167, 62, 68);
        for (int y = 65; y <= 82; y++) {
            int x = 175 - (y - 65) / 2;
            rect(x, x + 2, y, y);
        }
        assertFalse(DetachedAnnotationInk.downBowRest(gray, W, H, 160, 60, 177, 87, G));
    }
    @Test
    public void detachedRaisedTextInitialNeedsTheOtherLetterBodies() {
        rect(153, 169, 60, 62);
        rect(166, 169, 63, 70);
        rect(153, 169, 71, 73);
        rect(153, 156, 63, 79);
        rect(150, 156, 80, 85);
        assertFalse(raisedWord());
        raisedWordNeighbors();
        assertTrue(raisedWord());
    }

    @Test
    public void genuineRestDiagonalIsNotPartOfTheRaisedTextRow() {
        rect(153, 160, 60, 66);
        for (int y = 65; y <= 85; y++) {
            int x = 171 - (y - 65) / 2;
            rect(x - 2, x, y, y);
        }
        raisedWordNeighbors();
        assertFalse(raisedWord());
    }

    private boolean raisedWord() {
        return SixteenthRestDetector.raisedEighthRestInitial(gray, W, H, 150, 171, 60, 85, 18);
    }

    private void raisedWordNeighbors() {
        rect(178, 181, 60, 72);
        rect(175, 178, 72, 83);
        rect(174, 179, 83, 85);
        rect(185, 197, 69, 71);
        rect(185, 187, 72, 83);
        rect(195, 197, 72, 83);
        rect(185, 196, 83, 85);
        rect(202, 204, 68, 79);
        rect(212, 215, 68, 79);
        rect(202, 214, 79, 80);
        rect(209, 212, 80, 88);
        rect(202, 209, 88, 90);
    }
}
