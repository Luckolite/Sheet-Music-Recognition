// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedSixteenthDyadsTest {
    private static final int WIDTH = 970, HEIGHT = 432;
    private static final int LEFT = 81, RIGHT = 849, STAFF_BOTTOM = 286, GAP = 16;
    private static final int FIRST = 120, SPACING = 50, UPPER = 242, LOWER = 258;
    private static final int PRIMARY = 322, SECONDARY = 308;

    @Test
    public void intactTerminalEighthClosesOrdinarySixteenthsAndKeepsIncomingTie() {
        var ink = page();
        var before = fixture(List.of());
        var after = ClosedSixteenthDyads.apply(before, ink, ink, WIDTH, HEIGHT);
        for (int i = 0; i < before.notes().size(); i++) {
            var note = before.notes().get(i);
            assertEquals(note.withTupletRatio(1, 1), after.notes().get(i));
        }
        assertEquals(2, after.notes().get(1).boundaryTies());
    }

    @Test
    public void eitherPlaneCanRetainARealBracketOrMissingRail() {
        var ink = page();
        var before = fixture(List.of());
        for (int y : new int[]{201, 358}) {
            var bracket = ink.clone();
            draw(bracket, 165, 207, y, y, (byte)0);
            draw(bracket, 231, 275, y, y, (byte)0);
            assertSame(before, ClosedSixteenthDyads.apply(before, ink, bracket, WIDTH, HEIGHT));
            assertSame(before, ClosedSixteenthDyads.apply(before, bracket, ink, WIDTH, HEIGHT));
        }
        var brokenRail = ink.clone();
        draw(brokenRail, 743, 755, PRIMARY - 2, PRIMARY + 2, (byte)255);
        assertSame(before, ClosedSixteenthDyads.apply(before, ink, brokenRail, WIDTH, HEIGHT));
        assertSame(before, ClosedSixteenthDyads.apply(before, brokenRail, ink, WIDTH, HEIGHT));
    }

    @Test
    public void anOwnedRestPreventsTheFilledBarInference() {
        var ink = page();
        var rest = new ScoreRestEvent(0, .5f, .72f, .01f, 0, 1, .25);
        var before = fixture(List.of(rest));
        assertSame(before, ClosedSixteenthDyads.apply(before, ink, ink, WIDTH, HEIGHT));
    }

    private static ScorePageInterpretation fixture(List<ScoreRestEvent> rests) {
        var region = new MeasureRegion(
                (float) LEFT / WIDTH, (float) RIGHT / WIDTH,
                (float) (STAFF_BOTTOM - 98) / HEIGHT, (float) (STAFF_BOTTOM + 33) / HEIGHT);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int col = 0; col < 15; col++) {
            int x = FIRST + col * SPACING;
            for (int y : new int[]{UPPER, LOWER}) {
                int ratio = col >= 1 && col <= 3 ? 3 : 1;
                var note = new ScoreNoteEvent(
                        0, (float) (x - LEFT) / (RIGHT - LEFT), (STAFF_BOTTOM - y) / 8,
                        0, 1, (float) y / HEIGHT, false, 0, col == 14 ? 1 : 2,
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, ratio, 0, 0, 30,
                        false, 0, false, 0, col == 0 && y == LOWER ? 2 : 0,
                        ratio == 3 ? 2 : 1, -1, ScoreNoteEvent.Kind.PITCHED,
                        ScoreNoteEvent.VisualSize.FULL, 0, null);
                notes.add(note);
            }
        }
        return new ScorePageInterpretation(List.of(region), notes, 0, List.of(),
                List.of(), List.of(new ScoreMeterChange(0, 4, 4)), rests);
    }

    private static byte[] page() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = STAFF_BOTTOM - 4 * GAP; y <= STAFF_BOTTOM; y += GAP)
            draw(ink, 26, WIDTH - 25, y, y, (byte)0);
        for (int barline : new int[]{LEFT, RIGHT})
            draw(ink, barline - 1, barline + 1, STAFF_BOTTOM - 4 * GAP, STAFF_BOTTOM, (byte)0);
        for (int col = 0; col < 15; col++) {
            int x = FIRST + col * SPACING;
            draw(ink, x - 9, x - 7, UPPER, PRIMARY + 2, (byte)0);
            draw(ink, x - 9, x + 7, UPPER - 4, UPPER + 4, (byte)0);
            draw(ink, x - 9, x + 7, LOWER - 4, LOWER + 4, (byte)0);
        }
        for (int start : new int[]{0, 4, 8, 12}) {
            int first = FIRST + start * SPACING - 9;
            int last = first + (start == 12 ? 2 : 3) * SPACING + 2;
            int secondaryLast = start == 12 ? first + SPACING + 2 : last;
            draw(ink, first, last, PRIMARY - 2, PRIMARY + 2, (byte)0);
            draw(ink, first, secondaryLast, SECONDARY - 2, SECONDARY + 2, (byte)0);
        }
        return ink;
    }

    private static void draw(byte[] ink, int left, int right, int top, int bottom, byte value) {
        for (int row = top; row <= bottom; row++)
            Arrays.fill(ink, row * WIDTH + left, row * WIDTH + right + 1, value);
    }
}
