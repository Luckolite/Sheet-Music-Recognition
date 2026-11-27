// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CompactTrebleClefTest {
    private static final int W = 280, H = 180, TOP = 50, GAP = 16, BOTTOM = TOP + 4 * GAP;
    private static final int LEFT = 106, TOP_OF_GLYPH = 48, RIGHT = 136, FOOT = 128;

    @Test
    public void completeStaffAndCompactTrebleLoopsCorroborateTheChange() {
        var page = new Scene();
        assertTrue(page.matches());
    }

    @Test
    public void openingEitherLoopOrRemovingPrintedRailsWithholdsTheChange() {
        var cut = new Scene();
        cut.paper(123, 54, 128, 56);
        assertFalse(cut.matches());
        var missing = new Scene();
        missing.paper(145, TOP + 2 * GAP, 230, TOP + 2 * GAP);
        assertFalse(missing.matches());
    }

    @Test
    public void cannotBorrowAnOverlappingNotehead() {
        var page = new Scene();
        page.ink(121, 97, 122, 98, OmrMeasurePostProcessor.NOTEHEAD);
        assertFalse(page.matches());
    }

    private static final class Scene {
        final byte[] gray = new byte[W * H], labels = new byte[W * H];

        Scene() {
            Arrays.fill(gray, (byte) 255);
            for (int i = 0; i < 5; i++)
                ink(18, TOP + i * GAP, W - 18, TOP + i * GAP, OmrMeasurePostProcessor.STAFF);
            int clef = OmrMeasurePostProcessor.CLEF_OR_KEY;
            frame(121, 54, 133, 72, clef);
            frame(108, 82, 124, 109, clef);
            frame(125, 94, 135, 109, clef);
            ink(123, TOP_OF_GLYPH, 124, FOOT, clef);
            ink(LEFT, 96, 108, 101, clef);
            ink(135, 100, RIGHT, 103, clef);
        }

        boolean matches() {
            return CompactTrebleClef.matches(
                    labels, gray, W, H, LEFT, TOP_OF_GLYPH, RIGHT, FOOT, BOTTOM, GAP);
        }

        void frame(int left, int top, int right, int bottom, int label) {
            ink(left, top, right, top + 1, label);
            ink(left, bottom - 1, right, bottom, label);
            ink(left, top, left + 1, bottom, label);
            ink(right - 1, top, right, bottom, label);
        }

        void ink(int left, int top, int right, int bottom, int label) {
            for (int y = top; y <= bottom; y++)
                for (int x = left; x <= right; x++) {
                    gray[y * W + x] = 0;
                    labels[y * W + x] = (byte) label;
                }
        }

        void paper(int left, int top, int right, int bottom) {
            for (int y = top; y <= bottom; y++)
                for (int x = left; x <= right; x++) gray[y * W + x] = (byte) 255;
        }
    }
}
