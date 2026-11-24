// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SixteenthBeamRailAliasTest {
    private static final int WIDTH = 980, HEIGHT = 402;
    private static final int LEFT = 54, RIGHT = 898, FIRST = 100, ADVANCE = 51;
    private static final int UPPER = 156, LOWER = 212, TAIL = 276;
    private static final MeasureRegion BAR =
            new MeasureRegion(
                    LEFT / (float) WIDTH, RIGHT / (float) WIDTH, 136f / HEIGHT, 267.2f / HEIGHT);

    @Test
    public void completeOctaveRowDropsOnlyTheRailReading() {
        var notes = notes();
        var pixels = page();
        assertEquals(notes.subList(0, 32), resolve(notes, pixels, pixels, 4));
        assertEquals(notes, resolve(notes, pixels, pixels, 3));
    }

    @Test
    public void completeTripleTimeOctavesDropAStemlessThirdRailReading() {
        var notes = new ArrayList<>(notes().subList(0, 24));
        int alias = FIRST + ADVANCE * 3 / 2;
        notes.add(head(alias, TAIL - 8, 0, 3, 0));
        var printed = page();
        assertEquals(notes.subList(0, 24), resolve(notes, printed, printed, 3));
        assertEquals(notes, resolve(notes, printed, printed, 4));

        var independent = printed.clone();
        paint(independent, alias - 11, TAIL - 9, alias - 7, TAIL + 39, 0);
        assertEquals(notes, resolve(notes, independent, printed, 3));
        assertEquals(notes, resolve(notes, printed, independent, 3));
    }

    @Test
    public void missingRailOrIndependentShaftOnEitherPlaneKeepsTheReading() {
        var notes = notes();
        var printed = page();
        var broken = printed.clone();
        int alias = FIRST + ADVANCE * 3 / 2;
        paint(broken, alias - 9, 260, alias + 10, 281, 255);
        assertEquals(notes, resolve(notes, printed, broken, 4));
        assertEquals(notes, resolve(notes, broken, printed, 4));

        var independent = printed.clone();
        paint(independent, alias - 11, 263, alias - 7, 320, 0);
        assertEquals(notes, resolve(notes, printed, independent, 4));
        assertEquals(notes, resolve(notes, independent, printed, 4));
    }

    @Test
    public void reverseTraceBelongsToTheExistingRightOctaveShaft() {
        var notes = new ArrayList<>(notes().subList(0, 32));
        int x = FIRST + ADVANCE * 2 - 20;
        var phantom =
                new ScoreNoteEvent(
                                0,
                                (x - LEFT) / (float) (RIGHT - LEFT),
                                0,
                                0,
                                1,
                                (TAIL - 8) / (float) HEIGHT,
                                false,
                                0,
                                0,
                                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                                ScoreNoteEvent.DURATION_QUARTER,
                                1,
                                0,
                                0,
                                ScoreNoteEvent.CLEF_TREBLE)
                        .withStemDirection(1);
        notes.add(phantom);
        var pixels = page();
        assertEquals(notes.subList(0, 32), resolve(notes, pixels, pixels, 4));

        var separate = pixels.clone();
        paint(separate, x - 11, TAIL - 10, x - 7, TAIL + 40, 0);
        assertEquals(notes, resolve(notes, pixels, separate, 4));
        assertEquals(notes, resolve(notes, separate, pixels, 4));
    }

    private static List<ScoreNoteEvent> resolve(
            List<ScoreNoteEvent> notes, byte[] decoded, byte[] original, int numerator) {
        return SixteenthBeamRailAlias.removeClosedOctaveAliases(
                notes,
                List.of(BAR),
                List.of(),
                new ScoreMeterChange(0, numerator, 4),
                decoded,
                original,
                WIDTH,
                HEIGHT);
    }

    private static List<ScoreNoteEvent> notes() {
        var rows = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 16; slot++) {
            int x = FIRST + slot * ADVANCE;
            rows.add(head(x, UPPER, 8, 2, -1));
            rows.add(head(x, LOWER, 1, 2, -1));
        }
        rows.add(head(FIRST + ADVANCE * 3 / 2, TAIL - 8, 0, 1, 0));
        return List.copyOf(rows);
    }

    private static ScoreNoteEvent head(int x, int y, int step, int beams, int direction) {
        return new ScoreNoteEvent(
                        0,
                        (x - LEFT) / (float) (RIGHT - LEFT),
                        step,
                        0,
                        1,
                        y / (float) HEIGHT,
                        false,
                        0,
                        beams,
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                        0,
                        1,
                        0,
                        0,
                        ScoreNoteEvent.CLEF_TREBLE)
                .withStemDirection(direction);
    }

    private static byte[] page() {
        var pixels = new byte[WIDTH * HEIGHT];
        Arrays.fill(pixels, (byte) 255);
        for (int i = 0; i < 5; i++) paint(pixels, LEFT, 164 + i * 16, RIGHT, 166 + i * 16, 0);
        for (int slot = 0; slot < 16; slot++) {
            int x = FIRST + slot * ADVANCE;
            paint(pixels, x - 10, UPPER, x - 7, TAIL, 0);
            oval(pixels, x, UPPER);
            oval(pixels, x, LOWER);
            if (slot % 4 == 0) {
                int terminal = x + 3 * ADVANCE - 7;
                paint(pixels, x - 10, TAIL - 7, terminal, TAIL, 0);
                paint(pixels, x - 10, TAIL - 19, terminal, TAIL - 12, 0);
            }
        }
        return pixels;
    }

    private static void oval(byte[] pixels, int x, int y) {
        for (int dy = -5; dy <= 5; dy++) {
            int half = (int) Math.round(9 * Math.sqrt(1 - dy * dy / 36.0));
            paint(pixels, x - half, y + dy, x + half + 1, y + dy + 1, 0);
        }
    }

    private static void paint(byte[] pixels, int x0, int y0, int x1, int y1, int value) {
        for (int y = y0; y < y1; y++)
            Arrays.fill(pixels, y * WIDTH + x0, y * WIDTH + x1, (byte) value);
    }
}
