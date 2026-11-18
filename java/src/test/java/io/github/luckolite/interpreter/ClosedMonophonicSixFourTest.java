// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedMonophonicSixFourTest {
    private static final int WIDTH = 950;
    private static final int HEIGHT = 550;
    private static final int GAP = 16;
    private static final int LEFT = 40;
    private static final int RIGHT = 810;
    private static final int BOTTOM = 350;
    private static final int FIRST = 73;
    private static final int HEAD_Y = 284;
    private static final int SPACING = 31;
    private static final int TOP_RAIL = 218;
    private static final int SECOND_RAIL = 231;
    private static final List<MeasureRegion> REGIONS =
            List.of(region(50, 860, 50, 120), region(LEFT, RIGHT, 219, BOTTOM),
                    region(RIGHT + 8, 910, 219, BOTTOM));

    @Test
    public void sixClosedDoubleGroupsDisproveAnOverlappingTriplet() {
        byte[] ink = ink();
        assertTrue(proves(ink, ink));

        byte[] noSecondary = ink.clone();
        fill(noSecondary, FIRST + 13, FIRST + SPACING - 1,
                SECOND_RAIL - 1, SECOND_RAIL + 6, 255);
        assertFalse(proves(ink, noSecondary));
    }

    @Test
    public void aThirdRailOrMissingClosingRuleRemainsDistinct() {
        byte[] ink = ink();
        byte[] third = ink.clone();
        fill(third, FIRST + 9, FIRST + 3 * SPACING + 11,
                SECOND_RAIL + 13, SECOND_RAIL + 17, 0);
        assertFalse(proves(ink, third));

        byte[] open = ink.clone();
        fill(open, RIGHT - 4, RIGHT + 13, BOTTOM - 104, BOTTOM - 24, 255);
        assertFalse(proves(ink, open));
    }

    private static boolean proves(byte[] normalized, byte[] original) {
        var notes = new ArrayList<ScoreNoteEvent>();
        var heads = new ArrayList<ClosedSixteenthDyads.Head>();
        for (int i = 0; i < 24; i++) {
            int x = FIRST + i * SPACING;
            var note = new ScoreNoteEvent(1, (float) (x - LEFT) / (RIGHT - LEFT), 4,
                            0, 1, (float) HEAD_Y / HEIGHT, false, 0, 2,
                            ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0)
                    .withClef(ScoreNoteEvent.CLEF_TREBLE).withStemDirection(1);
            if (i >= 2 && i <= 4) note = note.withTupletRatio(3, 2);
            notes.add(note);
            heads.add(new ClosedSixteenthDyads.Head(i, note, x, HEAD_Y));
        }
        return ClosedMonophonicSixFour.proves(
                new ClosedSixteenthDyads.Ink(normalized, WIDTH, HEIGHT),
                new ClosedSixteenthDyads.Ink(original, WIDTH, HEIGHT), REGIONS, 1, heads, notes);
    }

    private static byte[] ink() {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        int staffBottom = BOTTOM - 2 * GAP;
        for (int y = staffBottom - 4 * GAP; y <= staffBottom; y += GAP) {
            fill(ink, 20, 921, y, y + 1, 0);
        }
        fill(ink, RIGHT + 3, RIGHT + 5, staffBottom - 4 * GAP, staffBottom + 1, 0);
        for (int i = 0; i < 24; i++) {
            int x = FIRST + i * SPACING;
            fill(ink, x - 8, x + 10, HEAD_Y - 3, HEAD_Y + 4, 0);
            fill(ink, x + 8, x + 11, TOP_RAIL, HEAD_Y, 0);
            if (i % 4 == 0) {
                fill(ink, x + 8, x + 3 * SPACING + 11, TOP_RAIL, TOP_RAIL + 5, 0);
                fill(ink, x + 8, x + 3 * SPACING + 11, SECOND_RAIL, SECOND_RAIL + 5, 0);
            }
        }
        return ink;
    }

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion((float) left / WIDTH, (float) right / WIDTH,
                (float) top / HEIGHT, (float) bottom / HEIGHT);
    }

    private static void fill(byte[] ink, int left, int right, int top, int bottom, int value) {
        for (int y = top; y < bottom; y++) {
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right, (byte) value);
        }
    }
}
