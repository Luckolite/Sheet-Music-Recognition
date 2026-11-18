// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ClosedTerminalOctaveBeforeCommonTest {
    private static final int WIDTH = 326;
    private static final int HEIGHT = 264;
    private static final int LEFT = 94;
    private static final int RIGHT = 186;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final byte[] labels = new byte[gray.length];

    public ClosedTerminalOctaveBeforeCommonTest() {
        Arrays.fill(gray, (byte) 255);
        drawStaff(gray, 40, 196);
        paint(gray, 94, 96, 80, 145, 0);
        paint(gray, 187, 189, 80, 145, 0);
        paint(gray, 194, 196, 80, 145, 0);
        flat(126, 72);
        flat(126, 128);
        paint(gray, 144, 146, 73, 154, 0);
        paint(gray, 144, 162, 68, 76, 0);
        paint(gray, 144, 162, 124, 132, 0);
    }

    @Test
    public void separatedPrintedFlatsAndAnOwnedShaftCloseAQuarterOctave() throws Exception {
        assertTrue(proves(gray));
    }

    @Test
    public void erasedClosingDoubleRuleOrSharedShaftAbstains() throws Exception {
        var open = gray.clone();
        paint(open, 187, 197, 78, 146, 255);
        drawStaff(open, 187, 197);
        assertFalse(proves(open));
        var disconnected = gray.clone();
        paint(disconnected, 143, 147, 87, 114, 255);
        assertFalse(proves(disconnected));
    }

    @Test
    public void bodyWithoutTheLowerFlatCannotClaimTheShortCadence() throws Exception {
        paint(labels, 124, 142, 95, 140, 0);
        assertFalse(proves(gray));
    }

    private boolean proves(byte[] original) throws Exception {
        var bar = new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH, 45f / HEIGHT, 177f / HEIGHT);
        var next = new MeasureRegion(51f / WIDTH, 296f / WIDTH, 192f / HEIGHT, 258f / HEIGHT);
        var heads = List.of(note(154, 72, 9), note(154, 128, 2));
        var method = PrintedCadenceDurations.class.getDeclaredMethod("closedTerminalOctaveBeforeCommon",
                byte[].class, byte[].class, byte[].class, int.class, int.class,
                MeasureRegion.class, MeasureRegion.class, List.class);
        method.setAccessible(true);
        return PrintedQuarterDyadAccidentals.provesClosedTripleSystemEnd(
                labels, gray, WIDTH, HEIGHT, bar, next, heads, true)
                && (boolean) method.invoke(null, labels, gray, original, WIDTH, HEIGHT, bar, next, heads);
    }

    private ScoreNoteEvent note(int x, int y, int step) {
        return new ScoreNoteEvent(0, (x - LEFT) / (float) (RIGHT - LEFT), step, 0, 1,
                y / (float) HEIGHT, false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FLAT, 1,
                1, 0, 0, 30, false, 0, false, 0, 0, 1, -1);
    }

    private void flat(int x, int y) {
        mark(x, x + 3, y - 28, y + 8);
        mark(x, x + 12, y - 9, y - 3);
        mark(x, x + 10, y - 2, y + 1);
        mark(x, x + 6, y + 2, y + 5);
    }

    private void mark(int left, int right, int top, int bottom) {
        paint(gray, left, right, top, bottom, 0);
        paint(labels, left, right, top, bottom, OmrMeasurePostProcessor.CLEF_OR_KEY);
    }

    private void drawStaff(byte[] image, int left, int right) {
        for (int y = 80; y <= 144; y += 16) paint(image, left, right, y, y + 1, 0);
    }

    private void paint(byte[] image, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(image, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
        }
    }
}
