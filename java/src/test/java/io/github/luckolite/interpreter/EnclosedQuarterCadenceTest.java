// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class EnclosedQuarterCadenceTest {
    private static final int WIDTH = 768;
    private static final int HEIGHT = 360;
    private static final int LEFT = 588;
    private static final int RIGHT = 724;
    private static final int TOP_RULE = 100;
    private static final int BOTTOM_RULE = 164;
    private static final int SHAFT = 618;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final byte[] labels = new byte[gray.length];
    private final MeasureRegion before = region(35, 570, 66, 198);
    private final MeasureRegion bar = region(LEFT, RIGHT, 66, 198);
    private final MeasureRegion next = region(35, 700, 210, 341);

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion(
                left / (float) WIDTH,
                right / (float) WIDTH,
                top / (float) HEIGHT,
                bottom / (float) HEIGHT);
    }

    private static void ink(byte[] plane, int x1, int x2, int y1, int y2, byte value) {
        for (int y = y1; y <= y2; y++)
            Arrays.fill(plane, y * WIDTH + x1, y * WIDTH + x2 + 1, value);
    }

    private void staff(boolean doubleBar) {
        Arrays.fill(gray, (byte) 255);
        for (int row = TOP_RULE; row <= BOTTOM_RULE; row += 16)
            ink(gray, 35, doubleBar ? RIGHT + 8 : RIGHT, row, row + 1, (byte) 0);
        ink(gray, 578, 580, TOP_RULE, BOTTOM_RULE + 1, (byte) 0);
        if (doubleBar) ink(gray, RIGHT + 6, RIGHT + 8, TOP_RULE, BOTTOM_RULE + 1, (byte) 0);
        else ink(gray, RIGHT - 2, RIGHT, TOP_RULE, BOTTOM_RULE + 1, (byte) 0);
        if (doubleBar) ink(gray, RIGHT - 1, RIGHT + 1, TOP_RULE, BOTTOM_RULE + 1, (byte) 0);
    }

    private void pairedDownShaft() {
        ink(gray, SHAFT, SHAFT + 2, 104, 171, (byte) 0);
        ink(labels, SHAFT, SHAFT + 2, 104, 171, OmrMeasurePostProcessor.STEM_OR_REST);
        for (int y : new int[] {105, 121}) {
            ink(gray, SHAFT + 2, SHAFT + 13, y - 2, y + 2, (byte) 0);
            ink(labels, SHAFT + 2, SHAFT + 13, y - 2, y + 2, OmrMeasurePostProcessor.NOTEHEAD);
        }
    }

    private static ScoreNoteEvent head(
            int measure, float x, int y, int step, int beam, float base, int direction) {
        return new ScoreNoteEvent(
                measure,
                x,
                step,
                0,
                1,
                y / (float) HEIGHT,
                false,
                0,
                beam,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                base,
                1,
                0,
                0,
                30,
                false,
                0,
                false,
                0,
                0,
                1,
                direction,
                ScoreNoteEvent.Kind.PITCHED,
                ScoreNoteEvent.VisualSize.FULL,
                0,
                null);
    }

    private boolean closes(boolean falseFlag) {
        var prior = new java.util.ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 12; i++) {
            prior.add(head(0, (i + 1) / 13f, 105, 7, 2, 0, -1));
            prior.add(head(0, (i + 1) / 13f, 121, 5, 2, 0, -1));
        }
        int beams = falseFlag ? 1 : 0;
        float base = falseFlag ? 0 : 1;
        return PrintedCadenceDurations.enclosedQuarterAfterSixteenths(
                labels,
                gray,
                WIDTH,
                HEIGHT,
                before,
                bar,
                next,
                prior,
                List.of(
                        head(1, .286f, 105, 7, beams, base, -1),
                        head(1, .298f, 121, 5, beams, base, -1)),
                falseFlag);
    }

    @Test
    public void recoversTheBareShaftOnlyWithoutAnAttachedFlag() {
        staff(true);
        pairedDownShaft();
        assertTrue(closes(true));
        ink(gray, SHAFT, SHAFT + 16, 168, 171, (byte) 0);
        assertFalse(closes(true));
    }

    @Test
    public void aSingleStrokeMustEndAllStaffRules() {
        staff(false);
        pairedDownShaft();
        assertTrue(closes(false));
        ink(gray, RIGHT, RIGHT + 24, 132, 133, (byte) 0);
        assertFalse(closes(false));
    }
}
