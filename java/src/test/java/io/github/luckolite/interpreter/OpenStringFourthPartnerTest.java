// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class OpenStringFourthPartnerTest {
    private static final int WIDTH = 442, HEIGHT = 432, GAP = 16;
    private static final int LEFT = 174, RIGHT = 310, HEAD = 219, FLOOR = 220;

    @Test
    public void provesStoppedFourthAndOpenStringOnBothStemDirections() {
        for (int direction : new int[] {-1, 1}) {
            var score = score(direction);
            byte[] ink = paper(direction);
            var recovered = OpenStringHarmonics.recover(score, 0);
            assertEquals(1, recovered.size());
            var result = OpenStringHarmonics.captureFourthPartners(
                    score, ink, ink, WIDTH, HEIGHT, new int[]{0}, recovered);
            var base = score.notes().get(0);
            assertEquals(java.util.Map.of(0, base.withOctaveShift(0).withHarmonicSemitones(24)), result);
        }
    }

    @Test
    public void eachHollowHeadAndContinuousShaftMustSurviveInBothPlanes() {
        int direction = 1;
        var score = score(direction);
        var recovered = OpenStringHarmonics.recover(score, 0);
        var ink = paper(direction);
        for (int[] erased : new int[][] {
                {HEAD - 12, FLOOR - 33, HEAD + 4, FLOOR - 15},
                {HEAD - 12, FLOOR - 57, HEAD + 4, FLOOR - 39},
                {HEAD + 7, FLOOR - 14, HEAD + 12, FLOOR - 11}}) {
            byte[] cut = ink.clone();
            rectangle(cut, erased[0], erased[1], erased[2], erased[3], (byte)255);
            assertTrue(OpenStringHarmonics.captureFourthPartners(
                    score, ink, cut, WIDTH, HEIGHT, new int[]{0}, recovered).isEmpty());
            assertTrue(OpenStringHarmonics.captureFourthPartners(
                    score, cut, ink, WIDTH, HEIGHT, new int[]{0}, recovered).isEmpty());
        }
    }

    @Test
    public void incompleteOrEstimatedQuarterCannotExposeAnUnpairedWriterMarker() {
        var event = new java.util.HashMap<String, Object>(java.util.Map.of(
                "durationBeats", 1.0, "startBeat", 3.0, "tiedFromPrevious", false, "durationFallback", false));
        assertTrue(Main.completeCompoundQuarter(event, 2, 4));
        assertTrue(!Main.completeCompoundQuarter(event, 2, 3.5));
        for (var obstruction : java.util.Map.of("durationBeats", .75, "durationFallback", true,
                "tiedFromPrevious", true, "writtenPlacement", "gesture").entrySet()) {
            var unrelated = new java.util.HashMap<>(event);
            unrelated.put(obstruction.getKey(), obstruction.getValue());
            assertTrue(!Main.completeCompoundQuarter(unrelated, 2, 4));
        }
    }

    private static ScorePageInterpretation score(int direction) {
        var bar = new MeasureRegion(LEFT/(float)WIDTH, RIGHT/(float)WIDTH, 122f/HEIGHT, 254f/HEIGHT);
        var base = new ScoreNoteEvent(0, (HEAD-LEFT)/(float)(RIGHT-LEFT), 0, 0, 1, FLOOR/(float)HEIGHT,
                false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE)
                .withStemDirection(direction).withOctaveShift(2)
                .withOpenStringTouch(new ScoreNoteEvent.OpenStringTouch(
                        6, (FLOOR-3*GAP)/(float)HEIGHT, ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        return new ScorePageInterpretation(List.of(bar), List.of(base), 0, List.of(), List.of(), List.of(), List.of());
    }

    private static byte[] paper(int direction) {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte)255);
        for (int y = FLOOR - GAP * 4; y <= FLOOR; y += GAP) {
            rectangle(ink, 23, y, 416, y, (byte)0);
        }
        int x = HEAD + 10 * direction;
        int top = direction > 0 ? FLOOR - 4 * GAP - 5 : FLOOR - 3 * GAP;
        int bottom = direction > 0 ? FLOOR : FLOOR + 45;
        rectangle(ink, x - 1, top, x, bottom, (byte)0);
        for (int y = -10; y <= 10; y++) {
            for (int dx = -10; dx <= 10; dx++) {
                int radius = Math.abs(dx) + Math.abs(y);
                if (radius >= 8 && radius <= 11) {
                    ink[(FLOOR - 24 + y) * WIDTH + HEAD + dx] = 0;
                    ink[(FLOOR - 48 + y) * WIDTH + HEAD + dx] = 0;
                }
                if (dx * dx / 100.0 + y * y / 28.0 <= 1)
                    ink[(FLOOR + y) * WIDTH + HEAD + dx] = 0;
            }
        }
        return ink;
    }

    private static void rectangle(byte[] ink, int left, int top, int right, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, value);
    }
}
