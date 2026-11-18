// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedQuarterTouchTest {
    private static final int WIDTH = 442, HEIGHT = 432;
    private static final int LEFT = 174, RIGHT = 286, HEAD = 219, FLOOR = 220, GAP = 16;
    private static final int STOPPED = FLOOR + 24, SHAFT = HEAD + 10;

    @Test
    public void decodedQuarterTouchNeedsADiamondAndSharedShaftOnBothRasters() {
        var touch = note(FLOOR, 0);
        var stopped = note(STOPPED, -3);
        var score = new ScorePageInterpretation(
                List.of(region(30, 154, 122, 254), region(LEFT, RIGHT, 122, 254),
                        region(30, 412, 274, 406)), List.of(touch, stopped), 0,
                List.of(new ScoreKeyChange(0, 0)), List.of(), List.of(new ScoreMeterChange(0, 4, 4)),
                List.of());
        var gray = new byte[WIDTH * HEIGHT];
        var labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int y = FLOOR - GAP * 4; y <= FLOOR; y += GAP)
            rectangle(gray, 23, y, RIGHT + 6, y, (byte) 0);
        rectangle(gray, 165, FLOOR - GAP * 4, 166, FLOOR, (byte) 0);
        rectangle(gray, RIGHT - 3, FLOOR - GAP * 4, RIGHT - 2, FLOOR, (byte) 0);
        rectangle(gray, RIGHT + 4, FLOOR - GAP * 4, RIGHT + 6, FLOOR, (byte) 0);
        rectangle(gray, SHAFT - 1, FLOOR - 40, SHAFT + 1, STOPPED, (byte) 0);
        for (int y = -11; y <= 11; y++) {
            for (int x = -11; x <= 11; x++) {
                int radius = Math.abs(x) + Math.abs(y);
                if (radius >= 8 && radius <= 11) {
                    int pixel = (FLOOR + y) * WIDTH + HEAD + x;
                    gray[pixel] = 0;
                    labels[pixel] = OmrMeasurePostProcessor.SYMBOL;
                }
                if (x * x / 100.0 + y * y / 28.0 <= 1)
                    gray[(STOPPED + y) * WIDTH + HEAD + x] = 0;
            }
        }
        var after = ArtificialHarmonics.recoverClosedQuarterTouches(score, labels, gray, gray, WIDTH, HEIGHT);
        assertEquals(List.of(stopped.withOctaveShift(2)), after.notes());
        assertEquals(score.measures(), after.measures());

        var cut = gray.clone();
        rectangle(cut, SHAFT - 1, FLOOR + 9, SHAFT + 1, FLOOR + 15, (byte) 255);
        assertSame(score, ArtificialHarmonics.recoverClosedQuarterTouches(score, labels, gray, cut, WIDTH, HEIGHT));
        assertSame(score, ArtificialHarmonics.recoverClosedQuarterTouches(score, labels, cut, gray, WIDTH, HEIGHT));

        var open = gray.clone();
        rectangle(open, RIGHT - 3, FLOOR - GAP * 4, RIGHT + 6, FLOOR, (byte) 255);
        assertSame(score, ArtificialHarmonics.recoverClosedQuarterTouches(score, labels, gray, open, WIDTH, HEIGHT));
    }

    @Test
    public void terminalHalfTouchDoesNotBecomeAThirdStoppedNote() {
        int xPosition = 196;
        int shaft = xPosition + 10;
        int upperY = STOPPED - 2 * GAP;
        int diamondY = upperY - 3 * GAP / 2;
        int touchY = STOPPED - 5 * GAP / 4;
        var lower = note(xPosition, STOPPED, -3, ScoreNoteEvent.DURATION_QUARTER);
        var upper = note(xPosition, upperY, 1, ScoreNoteEvent.DURATION_QUARTER);
        var touch = note(xPosition, touchY, -1, ScoreNoteEvent.DURATION_HALF).withStemDirection(0);
        var score = new ScorePageInterpretation(
                List.of(region(30, 154, 122, 254), region(LEFT, RIGHT, 122, 254)),
                List.of(upper, touch, lower), 0,
                List.of(new ScoreKeyChange(0, 0)), List.of(), List.of(new ScoreMeterChange(0, 2, 4)),
                List.of());
        var gray = new byte[WIDTH * HEIGHT];
        var labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int y = FLOOR - GAP * 4; y <= FLOOR; y += GAP)
            rectangle(gray, 23, y, RIGHT + 42, y, (byte) 0);
        rectangle(gray, 165, FLOOR - GAP * 4, 166, FLOOR, (byte) 0);
        rectangle(gray, RIGHT - 3, FLOOR - GAP * 4, RIGHT - 2, FLOOR, (byte) 0);
        rectangle(gray, RIGHT + 4, FLOOR - GAP * 4, RIGHT + 6, FLOOR, (byte) 0);
        rectangle(gray, shaft - 1, diamondY - 40, shaft + 1, STOPPED, (byte) 0);
        for (int y = -11; y <= 11; y++) {
            for (int x = -11; x <= 11; x++) {
                int radius = Math.abs(x) + Math.abs(y);
                if (radius >= 8 && radius <= 11) {
                    int pixel = (diamondY + y) * WIDTH + xPosition + x;
                    gray[pixel] = 0;
                    labels[pixel] = OmrMeasurePostProcessor.SYMBOL;
                }
                double head = x * x / 100.0 + y * y / 28.0;
                if (head <= 1) {
                    gray[(STOPPED + y) * WIDTH + xPosition + x] = 0;
                    gray[(upperY + y) * WIDTH + xPosition + x] = 0;
                }
                double cavity = x * x / 112.0 + y * y / 42.0;
                if (cavity > .50 && cavity <= 1)
                    gray[(touchY + y) * WIDTH + xPosition + 4 + x] = 0;
            }
        }
        var after = ArtificialHarmonics.recoverClosedQuarterTouches(score, labels, gray, gray, WIDTH, HEIGHT);
        assertEquals(List.of(upper, lower), after.notes());

        var severed = gray.clone();
        rectangle(severed, shaft - 2, diamondY + 5, shaft + 2, upperY - 6, (byte) 255);
        assertSame(score, ArtificialHarmonics.recoverClosedQuarterTouches(score, labels, gray, severed, WIDTH, HEIGHT));
    }

    private static ScoreNoteEvent note(int y, int step) {
        return note(HEAD, y, step, ScoreNoteEvent.DURATION_QUARTER);
    }

    private static ScoreNoteEvent note(int x, int y, int step, float duration) {
        return new ScoreNoteEvent(1, (x - LEFT) / (float) (RIGHT - LEFT), step, 0, 1,
                y / (float) HEIGHT, false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                duration, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE)
                .withStemDirection(1);
    }

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH,
                top / (float) HEIGHT, bottom / (float) HEIGHT);
    }

    private static void rectangle(byte[] pixels, int left, int top, int right, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(pixels, y * WIDTH + left, y * WIDTH + right + 1, value);
    }
}