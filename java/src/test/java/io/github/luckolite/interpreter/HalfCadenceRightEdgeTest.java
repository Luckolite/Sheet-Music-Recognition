// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class HalfCadenceRightEdgeTest {
    private static final int WIDTH = 600;
    private static final int HEIGHT = 246;
    private static final int TOP_RULE = 60;
    private static final int BOTTOM_RULE = 124;
    private static final int GAP = 16;
    private static final int SPLIT = 251;
    private static final int FITTED_LEFT = 260;
    private static final int FITTED_RIGHT = 386;
    private static final int FIRST_CLOSING_STROKE = 394;
    private static final int SECOND_CLOSING_STROKE = 401;

    @Test
    public void readsAnIsolatedHalfDyadWhenTheClosingDoubleBarExtendsPastTheFit() {
        assertArrayEquals(new float[] {4, 2, 4}, render(true, true), 0);
    }

    @Test
    public void oneClosingStrokeCannotShortenTheMeter() {
        assertArrayEquals(new float[] {4, 4, 4}, render(true, false), 0);
    }

    @Test
    public void aClosingDoubleBarAloneCannotProveAnUnprintedLeftSplit() {
        assertArrayEquals(new float[] {4, 4, 4}, render(false, true), 0);
    }

    @Test
    public void inlineMeterAfterDoubleBarCanLeaveAWideInterMeasureGap() {
        assertArrayEquals(new float[] {4, 2, 3}, render(true, true, true, true), 0);
    }

    @Test
    public void inlineMeterRequiresBothClosingRulesOnBothRasterPlanes() {
        assertArrayEquals(new float[] {4, 4, 3}, render(true, true, true, false), 0);
        assertArrayEquals(new float[] {4, 4, 3}, render(true, false, true, true), 0);
    }

    @Test
    public void inlineMeterStillRequiresAClosedOpeningRule() {
        assertArrayEquals(new float[] {4, 4, 3}, render(false, true, true, true), 0);
    }

    private static float[] render(boolean leftSplit, boolean secondClosingStroke) {
        return render(leftSplit, secondClosingStroke, false, true);
    }

    private static float[] render(
            boolean leftSplit, boolean secondClosingStroke, boolean inlineMeter, boolean sourceMatches) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP_RULE; y <= BOTTOM_RULE; y += GAP)
            Arrays.fill(gray, y * WIDTH + 20, y * WIDTH + SECOND_CLOSING_STROKE + 3, (byte) 0);
        if (leftSplit) stroke(gray, SPLIT, 3, TOP_RULE, BOTTOM_RULE);
        stroke(gray, FIRST_CLOSING_STROKE, 2, TOP_RULE, BOTTOM_RULE);
        if (secondClosingStroke) stroke(gray, SECOND_CLOSING_STROKE, 3, TOP_RULE, BOTTOM_RULE);

        hollowHead(gray, 293, 132);
        hollowHead(gray, 294, 88);
        stroke(gray, 300, 2, 71, 133);

        var measures = List.of(
                new MeasureRegion(35f / WIDTH, 242f / WIDTH, 30f / HEIGHT, 162f / HEIGHT),
                new MeasureRegion(FITTED_LEFT / (float) WIDTH, FITTED_RIGHT / (float) WIDTH,
                        30f / HEIGHT, 162f / HEIGHT),
                new MeasureRegion(32f / WIDTH, 365f / WIDTH, 173f / HEIGHT, 234f / HEIGHT));
        var chord = List.of(halfHead(293, 132, -1), halfHead(294, 88, 4));
        if (inlineMeter) {
            measures = List.of(measures.get(0), measures.get(1),
                    new MeasureRegion(444f / WIDTH, 550f / WIDTH, 30f / HEIGHT, 162f / HEIGHT));
            var score = new ScorePageInterpretation(measures, chord, 1, List.of(), List.of(),
                    List.of(new ScoreMeterChange(2, 3, 4)));
            byte[] source = gray.clone();
            if (!sourceMatches)
                for (int y = TOP_RULE; y <= BOTTOM_RULE; y++)
                    if ((y - TOP_RULE) % GAP != 0)
                        Arrays.fill(source, y * WIDTH + SECOND_CLOSING_STROKE,
                                y * WIDTH + SECOND_CLOSING_STROKE + 3, (byte) 255);
            byte[] labels = new byte[gray.length];
            for (int y = TOP_RULE; y <= BOTTOM_RULE; y += GAP)
                Arrays.fill(labels, y * WIDTH + 20, y * WIDTH + SECOND_CLOSING_STROKE + 3,
                        OmrMeasurePostProcessor.STAFF);
            float[] beats = {4, 4, 3};
            PrintedCadenceDurations.apply(
                    PrintedCadenceDurations.captureWithRejectedLedgerRows(
                            score, labels, gray, WIDTH, HEIGHT, List.of(), source), beats);
            return beats;
        }
        var score = new ScorePageInterpretation(measures, chord);
        var staffs = List.of(new PlayingTechniqueDetector.Staff(TOP_RULE, BOTTOM_RULE, GAP, 0, 1));
        float[] beats = {4, 4, 4};
        PrintedCadenceDurations.apply(
                PrintedCadenceDurations.capture(score, gray, WIDTH, HEIGHT, staffs), beats);
        return beats;
    }

    private static ScoreNoteEvent halfHead(int centerX, int centerY, int staffStep) {
        float position = (centerX - FITTED_LEFT) / (float) (FITTED_RIGHT - FITTED_LEFT);
        return new ScoreNoteEvent(
                1, position, staffStep, 0, 1, centerY / (float) HEIGHT, false,
                0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 2, 1, 0, 0,
                30, false, 0, false, 0, 0, 1, 1, ScoreNoteEvent.Kind.PITCHED);
    }

    private static void hollowHead(byte[] gray, int centerX, int centerY) {
        for (int y = -5; y <= 5; y++)
            for (int x = -7; x <= 7; x++) {
                float oval = x * x / 49f + y * y / 25f;
                if (oval <= 1 && oval >= .45f)
                    gray[(centerY + y) * WIDTH + centerX + x] = 0;
            }
    }

    private static void stroke(byte[] gray, int x, int thickness, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + x, y * WIDTH + x + thickness, (byte) 0);
    }
}
