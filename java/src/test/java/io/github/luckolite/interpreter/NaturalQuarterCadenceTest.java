// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class NaturalQuarterCadenceTest {
    private static final int WIDTH = 560;
    private static final int HEIGHT = 230;
    private static final int GAP = 16;
    private static final int TOP_RULE = 60;
    private static final int BOTTOM_RULE = TOP_RULE + 4 * GAP;
    private static final int LEFT = 260;
    private static final int RIGHT = 397;
    private static final int LOWER_X = 308;
    private static final int LOWER_Y = 76;
    private static final int UPPER_Y = 68;

    @Test
    public void completeDisplacedNaturalAllowsThePrintedQuarterCadence() {
        assertArrayEquals(new float[] {3, 1, 4},
                render(true, true, false), 0);
    }

    @Test
    public void incompleteNaturalDoesNotAuthorizeOtherInk() {
        assertArrayEquals(new float[] {3, 3, 4},
                render(false, true, false), 0);
    }

    @Test
    public void completeNaturalDoesNotAuthorizeASeparateMark() {
        assertArrayEquals(new float[] {3, 3, 4},
                render(true, true, true), 0);
    }

    @Test
    public void rawNaturalRequiresTheAccidentalOwnerOnTheChord() {
        assertArrayEquals(new float[] {3, 3, 4},
                render(true, false, false), 0);
    }

    private static float[] render(
            boolean completeNatural, boolean writtenNatural, boolean competingInk) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP_RULE; y <= BOTTOM_RULE; y += GAP)
            Arrays.fill(gray, y * WIDTH + 20, y * WIDTH + RIGHT + 5, (byte) 0);
        ink(gray, 250, TOP_RULE, 250, BOTTOM_RULE, 3);
        ink(gray, RIGHT - 6, TOP_RULE, RIGHT - 6, BOTTOM_RULE, 3);
        ink(gray, RIGHT + 1, TOP_RULE, RIGHT + 1, BOTTOM_RULE, 3);

        int naturalLeft = 278;
        ink(gray, naturalLeft, LOWER_Y - 22, naturalLeft, LOWER_Y + 8, 4);
        if (completeNatural) {
            int naturalRight = naturalLeft + 10;
            ink(gray, naturalRight, LOWER_Y - 8, naturalRight, LOWER_Y + 21, 4);
            ink(gray, naturalLeft, LOWER_Y - 5, naturalRight, LOWER_Y - 8, 5);
            ink(gray, naturalLeft, LOWER_Y + 8, naturalRight, LOWER_Y + 5, 5);
        }
        if (competingInk) ink(gray, 353, 84, 353, 101, 3);

        solidHead(gray, LOWER_X, LOWER_Y);
        solidHead(gray, LOWER_X - 1, UPPER_Y);
        ink(gray, LOWER_X - 7, UPPER_Y, LOWER_X - 7, LOWER_Y + 30, 2);

        var score = new ScorePageInterpretation(List.of(
                new MeasureRegion(26f / WIDTH, 240f / WIDTH, 32f / HEIGHT, 157f / HEIGHT),
                new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH,
                        32f / HEIGHT, 157f / HEIGHT),
                new MeasureRegion(29f / WIDTH, 350f / WIDTH, 172f / HEIGHT, 223f / HEIGHT)),
                List.of(quarter(LOWER_X - 1, UPPER_Y, 7, ScoreNoteEvent.ACCIDENTAL_FROM_KEY),
                        quarter(LOWER_X, LOWER_Y, 6, writtenNatural
                                ? ScoreNoteEvent.ACCIDENTAL_NATURAL : ScoreNoteEvent.ACCIDENTAL_FROM_KEY)));
        var staff = new PlayingTechniqueDetector.Staff(TOP_RULE, BOTTOM_RULE, GAP, 0, 1);
        float[] beats = {3, 3, 4};
        PrintedCadenceDurations.apply(
                PrintedCadenceDurations.capture(score, gray, WIDTH, HEIGHT, List.of(staff)), beats);
        return beats;
    }

    private static ScoreNoteEvent quarter(int x, int y, int step, int accidental) {
        return new ScoreNoteEvent(
                1, (x - LEFT) / (float) (RIGHT - LEFT), step, 0, 1, y / (float) HEIGHT,
                false, 0, 0, accidental, 1, 1, 0, 0, 30,
                false, 0, false, 0, 0, 1, -1, ScoreNoteEvent.Kind.PITCHED);
    }

    private static void solidHead(byte[] gray, int x, int y) {
        for (int dy = -5; dy <= 5; dy++)
            for (int dx = -7; dx <= 7; dx++)
                if (dx * dx / 49f + dy * dy / 25f <= 1)
                    gray[(y + dy) * WIDTH + x + dx] = 0;
    }

    private static void ink(
            byte[] gray, int x0, int y0, int x1, int y1, int thickness) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= steps; i++) {
            int x = Math.round(x0 + (x1 - x0) * i / (float) steps);
            int y = Math.round(y0 + (y1 - y0) * i / (float) steps);
            for (int dy = 0; dy < thickness; dy++)
                Arrays.fill(gray, (y + dy) * WIDTH + x,
                        (y + dy) * WIDTH + x + thickness, (byte) 0);
        }
    }
}
