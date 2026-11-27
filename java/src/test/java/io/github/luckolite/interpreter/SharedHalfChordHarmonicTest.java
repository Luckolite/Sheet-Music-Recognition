// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SharedHalfChordHarmonicTest {
    private static final int WIDTH = 240, HEIGHT = 240, X = 100, Y = 146, GAP = 18;

    @Test
    public void fourHollowHeadsKeepTheirPitchesThroughHarmonicRecognition() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int center : new int[]{Y, Y - 27, Y - 45, Y - 63}) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dx = -12; dx <= 12; dx++) {
                    double radiusSquared = dx * dx / 144d + dy * dy / 64d;
                    if (radiusSquared >= .64 * .64 && radiusSquared <= 1)
                        ink[(center + dy) * WIDTH + X + dx] = 0;
                }
            }
        }
        for (int rule = 0; rule < 5; rule++) {
            int row = (Y - GAP + GAP * rule) * WIDTH;
            Arrays.fill(ink, row + 15, row + 225, (byte) 0);
        }
        for (int ledger = 1; ledger <= 3; ledger++) {
            int row = (Y - GAP - GAP * ledger) * WIDTH;
            Arrays.fill(ink, row + X - 17, row + X + 18, (byte) 0);
        }
        for (int y = Y - 63; y <= Y + 38; y++)
            Arrays.fill(ink, y * WIDTH + X - 9, y * WIDTH + X - 7, (byte) 0);
        // A narrow scan stroke connects opposite sides of the lower pair's outlines.
        for (int y = Y - 31; y <= Y - 2; y++)
            Arrays.fill(ink, y * WIDTH + X + 9, y * WIDTH + X + 11, (byte) 0);

        var notes = List.of(half(6, Y), half(9, Y - 27), half(11, Y - 45), half(13, Y - 63));
        var result = ArtificialHarmonics.apply(null, ink, ink, WIDTH, HEIGHT,
                List.of(new MeasureRegion(0, 1, 0, 1)), notes,
                List.of(new PlayingTechniqueDetector.Staff(Y - GAP, Y + 3 * GAP, GAP, 1, 2)));
        assertEquals(notes, result);
    }

    private static ScoreNoteEvent half(int step, int y) {
        return new ScoreNoteEvent(
                0, X / (float) WIDTH, step, 1, 2, y / (float) HEIGHT, false, 0, 0,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, ScoreNoteEvent.DURATION_HALF, 1, 0, 0,
                ScoreNoteEvent.CLEF_BASS).withStemDirection(-1);
    }
}
