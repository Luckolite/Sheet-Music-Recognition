// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class JoinedDyadRailAliasTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 420;
    private static final int LEFT = 120;
    private static final int RIGHT = 875;
    private static final int FIRST = 170;
    private static final int STEP = 43;
    private static final int UPPER = 209;
    private static final int LOWER = 252;
    private static final int SECONDARY = 319;
    private static final int PRIMARY = 332;
    private static final int FIRST_ALIAS = FIRST + STEP - 27;
    private static final int SECOND_ALIAS = FIRST + 5 * STEP - 13;

    @Test
    public void removesOnlyFragmentsOwnedByTwoJoinedDyadRails() {
        var original = score();
        var gray = image();
        var fixed = ClosedDottedSixteenths.apply(original, gray, WIDTH, HEIGHT);
        assertEquals(32, fixed.notes().size());
        assertEquals(original.notes().subList(0, 32), fixed.notes());
        assertEquals(original.measures(), fixed.measures());

        var gapInRail = gray.clone();
        paint(
                gapInRail,
                FIRST_ALIAS - 8,
                FIRST_ALIAS + 8,
                SECONDARY - 4,
                SECONDARY + 4,
                (byte) 255);
        assertSame(original, ClosedDottedSixteenths.apply(original, gapInRail, WIDTH, HEIGHT));

        var competingShaft = gray.clone();
        paint(
                competingShaft,
                FIRST_ALIAS + 8,
                FIRST_ALIAS + 10,
                PRIMARY - 58,
                PRIMARY - 2,
                (byte) 0);
        assertSame(original, ClosedDottedSixteenths.apply(original, competingShaft, WIDTH, HEIGHT));
    }

    private static ScorePageInterpretation score() {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 16; i++) {
            int x = FIRST + i * STEP;
            int dots = i % 2 == 0 ? 1 : 0;
            int beams = dots == 1 ? 2 : 3;
            notes.add(note(x, UPPER, 15, dots, beams, 0, -1));
            notes.add(note(x, LOWER, 10, dots, beams, 0, -1));
        }
        notes.add(note(FIRST_ALIAS, PRIMARY - 3, 2, 0, 0, 1, 0));
        notes.add(note(SECOND_ALIAS, SECONDARY, 2, 0, 0, 1, 1));
        return new ScorePageInterpretation(
                List.of(
                        new MeasureRegion(
                                LEFT / (float) WIDTH,
                                RIGHT / (float) WIDTH,
                                200f / HEIGHT,
                                331f / HEIGHT)),
                notes,
                0,
                List.of(),
                List.of(),
                List.of(new ScoreMeterChange(0, 4, 4)));
    }

    private static ScoreNoteEvent note(
            int x, int y, int pitch, int dots, int beams, int unbeamed, int stem) {
        return new ScoreNoteEvent(
                0,
                (x - LEFT) / (float) (RIGHT - LEFT),
                pitch,
                0,
                1,
                y / (float) HEIGHT,
                false,
                dots,
                beams,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                unbeamed,
                1,
                0,
                0,
                ScoreNoteEvent.CLEF_TREBLE,
                false,
                0,
                false,
                0,
                0,
                1,
                stem,
                ScoreNoteEvent.Kind.PITCHED);
    }

    private static byte[] image() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int i = 0; i < 16; i++) {
            int x = FIRST + STEP * i;
            paint(gray, x - 10, x - 8, UPPER, PRIMARY + 3, (byte) 0);
            paint(gray, x - 8, x + 8, UPPER - 5, UPPER + 5, (byte) 0);
            paint(gray, x - 8, x + 8, LOWER - 5, LOWER + 5, (byte) 0);
            if (i % 2 == 0) {
                dot(gray, x + 20, UPPER - 4);
                dot(gray, x + 20, LOWER - 4);
                paint(gray, x - 10, x + STEP - 8, PRIMARY - 4, PRIMARY + 3, (byte) 0);
                paint(gray, x - 10, x + STEP - 8, SECONDARY - 3, SECONDARY + 4, (byte) 0);
            } else {
                paint(gray, x - 21, x - 8, SECONDARY - 12, SECONDARY - 6, (byte) 0);
            }
        }
        return gray;
    }

    private static void dot(byte[] gray, int x, int y) {
        for (int dy = -3; dy <= 3; dy++)
            for (int dx = -3; dx <= 3; dx++)
                if (dx * dx + dy * dy <= 10) gray[(y + dy) * WIDTH + x + dx] = 0;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, value);
    }
}
