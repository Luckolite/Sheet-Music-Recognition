// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ShiftedTerminalQuarterTest {
    private static final int WIDTH = 768;
    private static final int HEIGHT = 360;
    private static final int LEFT = 588;
    private static final int RIGHT = 700;
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    @Test
    public void acceptsLocalRulesWhenPageWideRulesAreShifted() {
        drawCadence();
        assertTrue(proves(List.of(new PlayingTechniqueDetector.Staff(117, 181, 16, 0, 1))));
        assertFalse(proves(List.of(new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 1))));
    }

    @Test
    public void requiresBothBarBoundariesAndAnIndependentShaft() {
        var shifted = List.of(new PlayingTechniqueDetector.Staff(117, 181, 16, 0, 1));
        drawCadence();
        ink(578, 580, 100, 165, (byte) 255);
        assertFalse(proves(shifted));

        drawCadence();
        ink(701, 705, 100, 165, (byte) 255);
        assertFalse(proves(shifted));

        drawCadence();
        ink(618, 620, 110, 133, (byte) 255);
        assertFalse(proves(shifted));
    }

    @Test
    public void rejectsDetachedInkInsideTheReportedCadence() {
        var shifted = List.of(new PlayingTechniqueDetector.Staff(117, 181, 16, 0, 1));
        drawCadence();
        ink(662, 667, 111, 153, (byte) 0);
        assertFalse(proves(shifted));
    }

    private boolean proves(List<PlayingTechniqueDetector.Staff> staffs) {
        return PrintedCadenceDurations.shiftedTerminalQuarter(
                gray, WIDTH, HEIGHT,
                new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH,
                        66 / (float) HEIGHT, 198 / (float) HEIGHT),
                null, staffs, List.of(head(105, 7), head(121, 5)));
    }

    private static ScoreNoteEvent head(int y, int step) {
        return new ScoreNoteEvent(
                0, 38f / (RIGHT - LEFT), step, 0, 1, y / (float) HEIGHT, false, 0, 0,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1, 1, 0, 0, 30, false, 0, false, 0, 0,
                1, -1, ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL, 0, null);
    }

    private void drawCadence() {
        Arrays.fill(gray, (byte) 255);
        for (int y = 100; y <= 164; y += 16) {
            ink(35, 705, y, y + 1, (byte) 0);
        }
        ink(578, 580, 100, 165, (byte) 0);
        ink(693, 695, 100, 165, (byte) 0);
        ink(702, 704, 100, 165, (byte) 0);
        ink(618, 620, 104, 147, (byte) 0);
        ink(619, 633, 102, 108, (byte) 0);
        ink(619, 633, 118, 124, (byte) 0);
    }

    private void ink(int left, int right, int top, int bottom, byte value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, value);
        }
    }
}
