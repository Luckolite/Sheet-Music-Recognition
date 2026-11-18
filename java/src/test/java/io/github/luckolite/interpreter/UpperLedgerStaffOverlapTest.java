// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0

package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class UpperLedgerStaffOverlapTest {
    private static final int WIDTH = 1024;
    private static final int HEIGHT = 576;
    private static final int GAP = 12;
    private static final int BOTTOM = 330;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final MeasureRegion overlapping = region(105, 922, 222, 319);
    private final List<MeasureRegion> printed =
            List.of(region(88, 320, 257, 355), region(338, 550, 257, 355),
                    region(568, 732, 257, 355), region(750, 940, 257, 355));
    private final List<MeasureRegion> all =
            List.of(printed.get(0), overlapping, printed.get(1), printed.get(2), printed.get(3));

    public UpperLedgerStaffOverlapTest() {
        Arrays.fill(gray, (byte) 255);
        drawStaff(BOTTOM);
    }

    @Test
    public void physicallyDetectedStaffSurvivesAnEmptyMeasureFrame() {
        var labels = new byte[WIDTH * HEIGHT];
        for (int i = 0; i < labels.length; i++)
            if (gray[i] == 0) labels[i] = OmrMeasurePostProcessor.STAFF;

        var expected = OmrScoreInterpreter.techniqueStaffs(labels, gray, WIDTH, HEIGHT, List.of());
        assertFalse(expected.isEmpty());
        var page = OmrScoreInterpreter.analyzePage(
                labels, gray, WIDTH, HEIGHT, List.of(), gray, List.of());
        assertEquals(expected, page.physicalStaffs());
        assertEquals(List.of(), page.score().notes());
    }

    @Test
    public void dropsLedgerRunsThatOverlapSeveralPrintedMeasures() {
        drawBarlines(329, 559, 741);
        for (int left : new int[] {131, 162, 435, 636, 686, 821})
            for (int i = 0; i < 3; i++)
                fill(left, 248 + i * GAP, left + 21, 250 + i * GAP);
        assertEquals(
                printed, PrintedMeasureRhythmGuard.rejectUpperLedgerRows(all, gray, WIDTH, HEIGHT));
    }

    @Test
    public void keepsAnUpperStaffThatHasItsOwnContinuousRules() {
        drawBarlines(329, 559, 741);
        drawStaff(294);
        assertSame(all, PrintedMeasureRhythmGuard.rejectUpperLedgerRows(all, gray, WIDTH, HEIGHT));
    }

    @Test
    public void oneLowerDividerCannotRejectAnUpperRegion() {
        drawBarlines(559);
        assertSame(all, PrintedMeasureRhythmGuard.rejectUpperLedgerRows(all, gray, WIDTH, HEIGHT));
    }

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion(
                left / (float) WIDTH, right / (float) WIDTH,
                top / (float) HEIGHT, bottom / (float) HEIGHT);
    }

    private void drawStaff(int bottom) {
        for (int line = 0; line < 5; line++) {
            int y = bottom - line * GAP;
            fill(87, y - 1, 942, y + 1);
        }
    }

    private void drawBarlines(int... xs) {
        for (int x : xs) fill(x, BOTTOM - 4 * GAP, x + 2, BOTTOM + 1);
    }

    private void fill(int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right, (byte) 0);
    }
}
