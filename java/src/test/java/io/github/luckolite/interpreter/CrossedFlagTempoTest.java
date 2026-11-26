// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;

public final class CrossedFlagTempoTest {
    @Test
    public void acceptsTwoEqualsStrokesCrossingAnOpeningEighthFlag() {
        assertEquals(List.of(new ScoreTempoChange(0, 0, 81.5, .5)), openingMark(true, true));
    }

    @Test
    public void requiresBothStrokesAndTheFlaggedNote() {
        assertEquals(List.of(), openingMark(false, true));
        assertEquals(List.of(), openingMark(true, false));
    }

    private static List<ScoreTempoChange> openingMark(boolean lowerStroke, boolean note) {
        int width = 620, height = 340;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int line = 0; line < 5; line++)
            ink(gray, width, 48, 180 + line * 18, 510, 1, 0);
        for (int x : new int[] {127, 145, 164}) ink(gray, width, x, 86, 7, 22, 0);
        ink(gray, width, 102, 93, 15, 2, 140);
        if (lowerStroke) ink(gray, width, 102, 99, 15, 2, 140);
        if (note) {
            ink(gray, width, 88, 50, 3, 69, 0);
            for (int y = 103; y <= 122; y++) {
                int radius = (int) Math.round(13 * Math.sqrt(Math.max(0, 1 - Math.pow((y - 112) / 11d, 2))));
                ink(gray, width, 78 - radius, y, radius * 2 + 1, 1, 0);
            }
            for (int y = 50; y <= 110; y++) {
                double turn = Math.sin((y - 50) / 60d * Math.PI * .71);
                int edge = 90 + Math.round((float) (turn * 21));
                int span = Math.max(3, 9 - (y - 50) / 9);
                ink(gray, width, Math.max(88, edge - span + 1), y,
                        Math.min(span, edge - 87), 1, 0);
            }
        }
        var numeral = new PlayingTechniqueDetector.Word(
                "163", 127f / width, 86f / height, 171f / width, 108f / height);
        return TempoChangeDetector.detectWithWords(List.of(), List.of(numeral), gray, width, height,
                List.of(new MeasureRegion(183f / width, .70f, .51f, .78f)));
    }

    private static void ink(byte[] gray, int width, int x, int y, int w, int h, int value) {
        for (int line = y; line < y + h; line++)
            Arrays.fill(gray, line * width + x, line * width + x + w, (byte) value);
    }
}
