// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class OpeningQuarterRestPairTest {
    private static final int WIDTH = 240, HEIGHT = 160;
    private static final ScoreRestEvent FIRST = new ScoreRestEvent(
            0, .4f, 71f / HEIGHT, 48f / HEIGHT, 0, 1, 1);

    @Test
    public void recoversASecondCompleteQuarterRest() throws Exception {
        var rests = read(2, 0);
        assertEquals(2, rests.size());
        assertEquals(1, rests.get(1).durationBeats(), 0);
        assertEquals(.59f, rests.get(1).positionInMeasure(), .005f);
    }

    @Test
    public void requiresInkInTheCompleteSecondRest() throws Exception {
        assertEquals(List.of(FIRST), read(0, 0));
        assertEquals(List.of(FIRST), read(1, 0));
    }

    @Test
    public void leavesAnOpeningEighthNoteAndItsRestAlone() throws Exception {
        assertEquals(List.of(FIRST), read(2, 1));
    }

    @SuppressWarnings("unchecked")
    private static List<ScoreRestEvent> read(int shape, int beam) throws Exception {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int row = 40; row <= 104; row += 16)
            Arrays.fill(gray, row * WIDTH + 18, row * WIDTH + 222, (byte) 0);
        drawZigzag(gray, 100, 96);
        if (shape > 0) drawZigzag(gray, 138, shape == 1 ? 77 : 96);
        var note = new ScoreNoteEvent(
                0, .87f, 0, 0, 1, .7f, false, 0, beam, 2, beam == 0 ? 1 : 0, 1)
                .withStemDirection(1);
        var recover = OmrScoreInterpreter.class.getDeclaredMethod(
                "recoverOpeningQuarterRest", byte[].class, int.class, int.class,
                List.class, List.class, List.class, List.class);
        recover.setAccessible(true);
        return (List<ScoreRestEvent>) recover.invoke(
                null, gray, WIDTH, HEIGHT, List.of(new MeasureRegion(20f / WIDTH, 220f / WIDTH, 0, 1)),
                List.of(new SixteenthRestDetector.Staff(40, 104, 16, 0, 1)),
                List.of(note), List.of(FIRST));
    }

    private static void drawZigzag(byte[] gray, int x, int endRow) {
        for (int row = 47; row < endRow; row++) {
            int dy = row - 47;
            int center = dy < 15 ? dy / 2 - 5 : dy < 32 ? 10 - dy / 2 : dy / 3 - 13;
            int half = dy < 8 || dy > 41 ? 2 : 4;
            Arrays.fill(gray, row * WIDTH + x + center - half,
                    row * WIDTH + x + center + half + 1, (byte) 0);
        }
    }
}
