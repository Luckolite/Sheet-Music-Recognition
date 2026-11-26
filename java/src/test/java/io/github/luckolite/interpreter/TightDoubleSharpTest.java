// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class TightDoubleSharpTest extends TouchingChordAccidentalTest {
    private void drawCross(boolean complete) {
        head(232, 136);
        for (int y = 0; y < 18; y++)
            for (int x = 0; x < 18; x++)
                if ((Math.abs(x - y) <= 4 || Math.abs(x + y - 17) <= 4)
                        && (complete || y <= 11))
                    rect(208 + x, 127 + y, 208 + x, 127 + y,
                            x <= 7 ? 3 : y >= 7 && y <= 9 ? 1 : 5);
    }

    @Test
    public void adjacentHeadDoesNotOccludeTheCompletePrintedCross() {
        drawCross(true);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP, accidental(notes(), 136));
    }

    @Test
    public void adjacentHeadCannotCompleteAMissingHalfCross() {
        drawCross(false);
        assertNotEquals(ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP, accidental(notes(), 136));
    }
}
