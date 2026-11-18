// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0

package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OverlappingQuarterCadenceTest {
    private static final int WIDTH = 2048;
    private static final int HEIGHT = 240;
    private static final int TOP = 60;
    private static final int BOTTOM = 124;
    private static final int GAP = 16;

    @Test
    public void terminalQuarterRequiresBothSeparatorsAndTheCompleteClosingRule() {
        byte[] gray = writtenRules();
        var proved = bounds(gray);
        assertEquals(3, proved.size());
        assertEquals(1, proved.get(2).cadenceQuarterBeats());

        for (int x : new int[] {1006, 1775, 1953}) {
            byte[] missing = gray.clone();
            for (int y = TOP; y <= BOTTOM; y += GAP)
                fill(missing, x - 2, x + 2, y + 3, Math.min(BOTTOM, y + 13), 255);
            assertTrue(bounds(missing).isEmpty());
        }
    }

    @Test
    public void independentLowerGuidesClearOnlyStaffLabels() {
        byte[] gray = writtenRules();
        var measured = bounds(gray);
        assertEquals(3, measured.size());
        byte[] labels = new byte[gray.length];
        for (int y : new int[] {142, 160}) {
            fill(gray, 290, 1855, y, y + 1, 0);
            fill(labels, 290, 1855, y, y + 2, OmrMeasurePostProcessor.STAFF);
        }
        int symbol = 142 * WIDTH + 900;
        labels[symbol] = OmrMeasurePostProcessor.SYMBOL;
        byte[] aligned =
                OverlappingQuarterCadence.withoutStaffLabelsOnLowerGuides(
                        labels, gray, WIDTH, HEIGHT, measured);
        assertNotSame(labels, aligned);
        assertEquals(OmrMeasurePostProcessor.SYMBOL, aligned[symbol]);
        assertEquals(0, aligned[142 * WIDTH + 901]);
        assertEquals(0, aligned[161 * WIDTH + 901]);
        assertEquals(OmrMeasurePostProcessor.STAFF, labels[142 * WIDTH + 901]);

        fill(gray, 290, 1855, 160, 161, 255);
        assertSame(
                labels,
                OverlappingQuarterCadence.withoutStaffLabelsOnLowerGuides(
                        labels, gray, WIDTH, HEIGHT, measured));
    }

    private static List<MeasureRegion> bounds(byte[] gray) {
        return OverlappingQuarterCadence.proveRules(
                gray, WIDTH, HEIGHT, region(200, 1052), region(1060, 1911), 16f);
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion(
                left / (float) WIDTH, right / (float) WIDTH, TOP / (float) HEIGHT, 191f / HEIGHT);
    }

    private static byte[] writtenRules() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += GAP) fill(gray, 125, 1955, y, y + 1, 0);
        for (int x : new int[] {1006, 1775, 1944, 1953}) fill(gray, x - 1, x + 1, TOP, BOTTOM, 0);
        return gray;
    }

    private static void fill(byte[] raster, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(raster, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
