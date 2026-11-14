// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class PrintedFingeringDigitsTest {
    private static final int WIDTH = 350, HEIGHT = 320;
    private static final MeasureRegion MEASURE = new MeasureRegion(.2f, .9f, .22f, .62f);
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    public PrintedFingeringDigitsTest() {
        Arrays.fill(gray, (byte) 255);
    }

    @Test
    public void capturesVisibleZeroAndOneOnTheirOwnAttackAndSide() {
        one(101, 91);
        zero(187, 168);
        var words = List.of(word("1", 101, 91), word("0", 187, 168));
        var events = List.of(event(101, 129, 0), event(187, 132, 1));
        assertEquals(
                List.of(
                        new PrintedFingeringDigits.Mark(0, 1, PrintedFingeringDigits.Placement.ABOVE),
                        new PrintedFingeringDigits.Mark(1, 0, PrintedFingeringDigits.Placement.BELOW)),
                capture(words, List.of(note(101, 129), note(187, 132)), events));
    }

    @Test
    public void rejectsDownBowMisreadAsOneAndAHeaderWithoutAnOwner() {
        rectangle(92, 82, 110, 84);
        rectangle(92, 82, 94, 100);
        rectangle(108, 82, 110, 100);
        one(18, 32);
        assertTrue(
                capture(
                                List.of(word("1", 101, 91), word("1", 18, 32)),
                                List.of(note(101, 129)),
                                List.of(event(101, 129, 0)))
                        .isEmpty());
    }

    @Test
    public void rejectsCueAndChordOwnersEvenWhenTheDigitInkIsValid() {
        one(101, 91);
        var words = List.of(word("1", 101, 91));
        var head = note(101, 129);
        assertTrue(
                capture(
                                words,
                                List.of(head.withVisualSize(ScoreNoteEvent.VisualSize.CUE)),
                                List.of(event(101, 129, 0)))
                        .isEmpty());
        assertTrue(
                capture(
                                words,
                                List.of(head, note(102, 151)),
                                List.of(event(101, 129, 0), event(102, 151, 0)))
                        .isEmpty());
    }

    @Test
    public void rejectsCompetingWordAndTupletDigit() {
        one(101, 91);
        var notes = List.of(note(101, 129));
        var events = List.of(event(101, 129, 0));
        assertTrue(capture(List.of(word("1", 101, 91), word("II", 110, 103)), notes, events)
                .isEmpty());
        assertTrue(capture(List.of(word("3", 101, 91)), notes, events).isEmpty());
    }

    @Test
    public void admitsAnOpticalThreeOnlyOnTheHeadSide() {
        int[][] rows = {
            {3, 8}, {1, 10}, {0, 11}, {0, 3, 7, 12}, {0, 2, 8, 12}, {0, 2, 8, 12},
            {8, 12}, {8, 12}, {7, 12}, {4, 10}, {4, 10}, {7, 11}, {8, 12}, {8, 12},
            {0, 2, 8, 12}, {0, 2, 8, 12}, {0, 2, 8, 12}, {0, 2, 8, 12},
            {0, 4, 7, 12}, {0, 11}, {2, 9}, {3, 8}
        };
        for (int row = 0; row < rows.length; row++)
            for (int i = 0; i < rows[row].length; i += 2)
                rectangle(95 + rows[row][i], 80 + row, 95 + rows[row][i + 1], 80 + row);
        var words = List.of(word("3", 101, 91));
        var events = List.of(event(101, 129, 0));
        var head = note(101, 129).withTupletRatio(3, 2);
        assertEquals(
                List.of(new PrintedFingeringDigits.Mark(0, 3, PrintedFingeringDigits.Placement.ABOVE)),
                capture(words, List.of(head.withStemDirection(-1)), events));
        assertTrue(capture(words, List.of(head.withStemDirection(1)), events).isEmpty());
        assertTrue(capture(words, List.of(head), events).isEmpty());
    }

    @Test
    public void rejectsAnOcrThreeOnDigitOneInk() {
        one(101, 91);
        assertTrue(
                capture(
                                List.of(word("3", 101, 91)),
                                List.of(note(101, 129).withStemDirection(-1)),
                                List.of(event(101, 129, 0)))
                        .isEmpty());
    }

    private List<PrintedFingeringDigits.Mark> capture(
            List<SheetInterpreter.Word> words,
            List<ScoreNoteEvent> notes,
            List<Map<String, Object>> events) {
        return PrintedFingeringDigits.capture(
                gray, WIDTH, HEIGHT, words, new ScorePageInterpretation(List.of(MEASURE), notes), events);
    }

    private static ScoreNoteEvent note(int x, int y) {
        return new ScoreNoteEvent(
                0, (x / (float) WIDTH - MEASURE.left()) / (MEASURE.right() - MEASURE.left()),
                5, 0, 1, y / (float) HEIGHT, false);
    }

    private static SheetInterpreter.Word word(String text, int x, int y) {
        return new SheetInterpreter.Word(
                text, (x - 13f) / WIDTH, (y - 14f) / HEIGHT, (x + 13f) / WIDTH, (y + 14f) / HEIGHT);
    }

    private static Map<String, Object> event(int x, int y, int onset) {
        return Map.of("x", x, "y", y, "startBeat", onset);
    }

    private void one(int x, int y) {
        rectangle(x - 2, y - 9, x + 2, y + 9);
        rectangle(x - 5, y - 7, x, y - 5);
        rectangle(x - 5, y + 7, x + 4, y + 9);
    }

    private void zero(int x, int y) {
        rectangle(x - 6, y - 7, x - 3, y + 7);
        rectangle(x + 3, y - 7, x + 6, y + 7);
        rectangle(x - 4, y - 9, x + 4, y - 6);
        rectangle(x - 4, y + 6, x + 4, y + 9);
    }

    private void rectangle(int l, int t, int r, int b) {
        for (int y = t; y <= b; y++)
            for (int x = l; x <= r; x++) gray[y * WIDTH + x] = 0;
    }
}
