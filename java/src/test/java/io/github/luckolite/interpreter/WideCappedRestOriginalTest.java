// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WideCappedRestOriginalTest {
    private static final int W = 560, H = 300;
    private static final MeasureRegion REGION = new MeasureRegion(
            84f / W, 510f / W, 74f / H, 190f / H);
    private static final MeasureNumberReconciler.NumberToken COUNT =
            new MeasureNumberReconciler.NumberToken(12, 279f / W, 80f / H, 291f / W, 100f / H);

    private static List<MeasureNumberReconciler.NumberToken> counts(
            boolean caps, boolean separateNote) {
        byte[] labels = new byte[W * H], gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        for (int row : new int[] {104, 118, 132, 146, 160}) {
            ink(gray, 35, row, 526, row, 0);
            ink(labels, 35, row, 526, row, OmrMeasurePostProcessor.STAFF);
        }
        ink(gray, 76, 126, 494, 139, 0);
        if (caps) {
            ink(gray, 76, 117, 78, 147, 0);
            ink(gray, 492, 117, 494, 147, 0);
        }
        // The segmentation model smears the thick H-rest into one wide head component.
        ink(labels, 90, 128, 480, 135, OmrMeasurePostProcessor.NOTEHEAD);
        ink(gray, 279, 83, 281, 100, 0);
        ink(gray, 285, 83, 291, 85, 0);
        ink(gray, 288, 86, 290, 93, 0);
        ink(gray, 285, 92, 288, 100, 0);
        ink(gray, 285, 98, 291, 100, 0);
        if (separateNote) {
            ink(gray, 445, 108, 455, 114, 0);
            ink(labels, 445, 108, 455, 114, OmrMeasurePostProcessor.NOTEHEAD);
        }
        return MultiMeasureRestDetector.detect(labels, gray, W, H, List.of(REGION), List.of(COUNT));
    }

    private static void ink(byte[] pixels, int l, int t, int r, int b, int value) {
        for (int y = t; y <= b; y++) Arrays.fill(pixels, y * W + l, y * W + r + 1, (byte) value);
    }

    @Test public void nearbyCappedBodyOwnsOnlyCoveredNoteheadLabels() {
        assertEquals(List.of(COUNT), counts(true, false));
    }

    @Test public void separateHeadStillBlocksCount() {
        assertTrue(counts(true, true).isEmpty());
    }

    @Test public void uncappedStripeDoesNotOwnNoteheadLabels() {
        assertTrue(counts(false, false).isEmpty());
    }
}
