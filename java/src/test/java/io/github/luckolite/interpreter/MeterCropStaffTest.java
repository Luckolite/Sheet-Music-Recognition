// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original staff drawings test geometry evidence, never numeral acceptance thresholds. */
public class MeterCropStaffTest {
    static final int W = 640, H = 300;

    static byte[] rails(int count, int first, float slope) {
        byte[] g = new byte[W * H];
        Arrays.fill(g, (byte) 235);
        for (int n = 0; n < count; n++)
            for (int x = 60; x < 580; x++) {
                int y = first + n * 14 + Math.round((x - 320) * slope);
                g[y * W + x] = 30;
            }
        return g;
    }

    static MeterChangeDetector.Crop crop(int first) {
        return new MeterChangeDetector.Crop(300, 94, 340, 165, first, 14);
    }

    @Test
    public void completeRailsCorrectShiftedHint() {
        var c = crop(102);
        var r = MeterCropStaff.refine(rails(5, 100, 0), W, H, c);
        assertEquals(100, r.firstLine());
        assertEquals(14, r.gap(), .2);
        assertEquals(c.left(), r.left());
        assertEquals(c.right(), r.right());
    }

    @Test
    public void alreadyAlignedStaffStaysAligned() {
        var r = MeterCropStaff.refine(rails(5, 100, 0), W, H, crop(100));
        assertEquals(100, r.firstLine());
        assertEquals(14, r.gap(), .2);
    }

    @Test
    public void fourRailsCannotRephaseMeter() {
        var c = crop(102);
        assertEquals(c, MeterCropStaff.refine(rails(4, 100, 0), W, H, c));
    }

    @Test
    public void blankPaperRetainsOriginal() {
        var c = crop(102);
        assertEquals(c, MeterCropStaff.refine(rails(0, 100, 0), W, H, c));
    }

    @Test
    public void disagreeingLocalPhasesRetainOriginal() {
        var c = crop(102);
        assertEquals(c, MeterCropStaff.refine(rails(5, 100, .08f), W, H, c));
    }

    @Test
    public void distantStaffCannotMoveCrop() {
        var c = crop(106);
        assertEquals(c, MeterCropStaff.refine(rails(5, 100, 0), W, H, c));
    }

    @Test
    public void malformedRasterRetainsOriginal() {
        var c = crop(102);
        assertEquals(c, MeterCropStaff.refine(new byte[3], W, H, c));
        assertEquals(c, MeterCropStaff.refine(null, W, H, c));
    }

    @Test
    public void invalidGapRetainsOriginal() {
        var c = new MeterChangeDetector.Crop(300, 94, 340, 165, 102, Float.NaN);
        assertSame(c, MeterCropStaff.refine(rails(5, 100, 0), W, H, c));
    }

    @Test
    public void clippedLowerRuleWithTwoPrefixWitnessesKeepsTheHeader() {
        var clipped = new MeterChangeDetector.Crop(300, 91, 340, 153, 95, 14);
        byte[] gray = rails(5, 100, 0);
        for (int line = 100; line <= 156; line += 14) {
            Arrays.fill(gray, (line - 1) * W + 60, (line - 1) * W + 580, (byte) 30);
            Arrays.fill(gray, (line + 1) * W + 60, (line + 1) * W + 580, (byte) 30);
        }
        for (int y = 95; y < 164; y++) Arrays.fill(gray, y * W + 342, y * W + 580, (byte) 235);
        for (int y = 104; y <= 124; y++) {
            for (int x = 307; x <= 323; x++) {
                if (x >= 316 || y <= 107 || y >= 121) gray[y * W + x] = 30;
            }
        }
        for (int y = 130; y <= 153; y++) {
            for (int x = 300; x <= 324; x++) {
                if (x >= 316 && x <= 321
                        || y >= 142 && y <= 145
                        || x >= 306 && x <= 310 && y <= 145) gray[y * W + x] = 30;
            }
        }

        var restored = MeterCropStaff.refine(gray, W, H, clipped);
        assertEquals(100, restored.firstLine());
        assertTrue(restored.bottom() > 156);
        assertEquals(clipped.top(), restored.top());
        assertEquals(clipped.left(), restored.left());
        assertEquals(clipped.right(), restored.right());

        for (int y = 155; y <= 157; y++)
            Arrays.fill(gray, y * W + clipped.left(), y * W + clipped.right(), (byte) 235);
        assertEquals(clipped, MeterCropStaff.refine(gray, W, H, clipped));
    }

    @Test
    public void sourcePixelsArePreserved() {
        var g = rails(5, 100, 0);
        var before = g.clone();
        MeterCropStaff.refine(g, W, H, crop(102));
        assertArrayEquals(before, g);
    }
}
