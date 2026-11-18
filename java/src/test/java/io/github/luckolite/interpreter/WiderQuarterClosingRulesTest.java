// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class WiderQuarterClosingRulesTest {
    private static final int WIDTH = 460, HEIGHT = 290;
    private static final int TOP = 88, GAP = 16, BOTTOM = TOP + 4 * GAP;
    private static final int LEFT = 198, RIGHT = 388;
    private static final int OPENING = 190, INNER = 384, OUTER = 393;
    private static final MeasureRegion BAR = region(LEFT, RIGHT, 54, 185);
    private static final MeasureRegion NEXT = region(32, 200, 199, 281);

    @Test
    public void widerOrdinaryQuarterRequiresAnIndependentOpeningAndTwoFullClosingRules() {
        var page = raster();
        assertTrue(accepts(page, NEXT, notes(1)));
        var noOpening = page.clone();
        eraseSpaces(noOpening, OPENING);
        assertFalse(accepts(noOpening, NEXT, notes(1)));
        var noSecondStroke = page.clone();
        eraseSpaces(noSecondStroke, OUTER);
        assertFalse(accepts(noSecondStroke, NEXT, notes(1)));

        var interrupted = page.clone();
        fill(interrupted, OUTER - 2, TOP + GAP + 2, OUTER + 2, TOP + 2 * GAP - 2, 255);
        assertFalse(accepts(interrupted, NEXT, notes(1)));
    }

    @Test
    public void extendingTheWidthDoesNotAdmitHarmonicOrNonCadenceShapes() {
        var page = raster();
        assertFalse(accepts(page, NEXT, notes(2)));
        assertFalse(accepts(page, NEXT, notes(1).stream().map(n -> n.withOctaveShift(2)).toList()));
        assertFalse(accepts(page, region(396, 440, 54, 185), notes(1)));

        var extraInteriorInk = page.clone();
        fill(extraInteriorInk, 320, TOP + GAP, 323, TOP + 3 * GAP, 0);
        assertFalse(accepts(extraInteriorInk, NEXT, notes(1)));
    }

    private static List<ScoreNoteEvent> notes(int duration) {
        return List.of(note(255, 4, duration), note(256, 0, duration));
    }

    private static ScoreNoteEvent note(int x, int step, int duration) {
        return new ScoreNoteEvent(
                0,
                (x - LEFT) / (float) (RIGHT - LEFT),
                step,
                0,
                1,
                (BOTTOM - step * GAP * .5f) / HEIGHT,
                false,
                0,
                0,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                duration,
                1,
                0,
                0,
                30,
                false,
                0,
                false,
                0,
                0,
                1,
                -1,
                ScoreNoteEvent.Kind.PITCHED);
    }

    private static boolean accepts(byte[] raw, MeasureRegion next, List<ScoreNoteEvent> chord) {
        return PrintedCadenceDurations.expandedTerminalDyad(raw, WIDTH, HEIGHT, BAR, next, chord);
    }

    private static byte[] raster() {
        byte[] raw = new byte[WIDTH * HEIGHT];
        Arrays.fill(raw, (byte) 255);
        for (int row = TOP; row <= BOTTOM; row += GAP) fill(raw, 32, row, OUTER + 2, row, 0);
        for (int x : new int[] {OPENING, INNER, OUTER}) fill(raw, x - 1, TOP, x + 1, BOTTOM, 0);
        fill(raw, 247, 116, 249, 176, 0);
        fill(raw, 248, 116, 261, 122, 0);
        fill(raw, 248, 148, 262, 154, 0);
        return raw;
    }

    private static void eraseSpaces(byte[] raw, int x) {
        for (int row = TOP; row < BOTTOM; row += GAP)
            fill(raw, x - 2, row + 1, x + 2, row + GAP - 1, 255);
    }

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion(
                (float) left / WIDTH,
                (float) right / WIDTH,
                (float) top / HEIGHT,
                (float) bottom / HEIGHT);
    }

    private static void fill(byte[] raw, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(raw, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
