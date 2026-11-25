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

    @Test
    public void splitsAdjacentFingeringDigitsOnlyWithTwoCompleteOpticalOwners() {
        one(101, 64);
        one(141, 64);
        rectangle(107, 82, 110, 133);
        rectangle(147, 82, 150, 133);
        rectangle(107, 82, 150, 86);
        rectangle(107, 91, 150, 95);
        var words = List.of(new SheetInterpreter.Word("1 1", 93f / WIDTH, 50f / HEIGHT,
                150f / WIDTH, 79f / HEIGHT));
        var notes = List.of(note(101, 133).withBeamAndStem(2, 1), note(141, 133).withBeamAndStem(2, 1));
        var score = new ScorePageInterpretation(List.of(MEASURE), notes);
        var events = List.of(event(101, 133, 0), event(141, 133, 1));
        assertEquals(List.of(
                new PrintedFingeringDigits.Mark(0, 1, PrintedFingeringDigits.Placement.ABOVE),
                new PrintedFingeringDigits.Mark(1, 1, PrintedFingeringDigits.Placement.ABOVE)),
                PrintedFingeringDigits.capture(gray, gray, WIDTH, HEIGHT, words, score, events));
        var incomplete = gray.clone();
        for (int y = 53; y <= 74; y++)
            Arrays.fill(incomplete, y * WIDTH + 135, y * WIDTH + 148, (byte) 255);
        assertTrue(PrintedFingeringDigits.capture(gray, incomplete, WIDTH, HEIGHT, words, score, events).isEmpty());
    }

    @Test
    public void retainsStaffCrossedOneBesideAnOpticallyConfirmedFingeringRun() {
        int[] positions = {101, 141, 181};
        rectangle(78, 103, 204, 105);
        one(101, 101);
        one(141, 82);
        one(181, 82);
        rectangle(107, 115, 110, 174);
        rectangle(147, 104, 150, 174);
        rectangle(187, 104, 190, 174);
        rectangle(107, 115, 190, 119);
        rectangle(147, 96, 190, 99);
        var words = List.of(new SheetInterpreter.Word("11", 131f / WIDTH, 68f / HEIGHT,
                192f / WIDTH, 95f / HEIGHT));
        var notes = Arrays.stream(positions).mapToObj(x -> note(x, 174).withBeamAndStem(2, 1)).toList();
        var score = new ScorePageInterpretation(List.of(MEASURE), notes);
        var events = List.of(event(101, 174, 0), event(141, 174, 1), event(181, 174, 2));
        var expected = List.of(
                new PrintedFingeringDigits.Mark(0, 1, PrintedFingeringDigits.Placement.ABOVE),
                new PrintedFingeringDigits.Mark(1, 1, PrintedFingeringDigits.Placement.ABOVE),
                new PrintedFingeringDigits.Mark(2, 1, PrintedFingeringDigits.Placement.ABOVE));
        assertEquals(expected, PrintedFingeringDigits.capture(gray, gray, WIDTH, HEIGHT, words, score, events));
        var erased = gray.clone();
        for (int y = 90; y <= 112; y++)
            if (y < 103 || y > 105) Arrays.fill(erased, y * WIDTH + 95, y * WIDTH + 108, (byte) 255);
        assertEquals(expected.subList(1, 3),
                PrintedFingeringDigits.capture(gray, erased, WIDTH, HEIGHT, words, score, events));
    }

    @Test
    public void retainsOpposedQuartetFingeringsInScoreOrder() {
        rectangle(78, 103, 244, 105);
        one(101, 101);
        for (int x : new int[] {141, 181, 221}) {
            one(x, 82);
            rectangle(x + 6, 104, x + 9, 174);
        }
        rectangle(107, 115, 110, 174);
        rectangle(107, 115, 230, 119);
        rectangle(147, 96, 230, 99);
        one(101, 198);
        one(141, 198);
        one(181, 198);
        one(221, 198);
        var words = List.of(new SheetInterpreter.Word("111", 131f / WIDTH, 68f / HEIGHT,
                232f / WIDTH, 95f / HEIGHT), word("1", 221, 198));
        var other = new MeasureRegion(.20f, .90f, .66f, .99f);
        var notes = List.of(note(101, 174).withBeamAndStem(2, 1), note(141, 174).withBeamAndStem(2, 1),
                note(181, 174).withBeamAndStem(2, 1), note(221, 174).withBeamAndStem(2, 1),
                new ScoreNoteEvent(1, .21f, 5, 0, 1, 262f / HEIGHT, false));
        var score = new ScorePageInterpretation(List.of(MEASURE, other), notes);
        var events = List.of(event(101, 174, 0), event(141, 174, 1), event(181, 174, 2),
                event(221, 174, 3), event(121, 262, 4));
        var full = PrintedFingeringDigits.capture(gray, gray, WIDTH, HEIGHT, words, score, events);
        assertEquals(List.of(
                new PrintedFingeringDigits.Mark(0, 1, PrintedFingeringDigits.Placement.BELOW),
                new PrintedFingeringDigits.Mark(1, 1, PrintedFingeringDigits.Placement.BELOW),
                new PrintedFingeringDigits.Mark(2, 1, PrintedFingeringDigits.Placement.BELOW),
                new PrintedFingeringDigits.Mark(3, 1, PrintedFingeringDigits.Placement.BELOW)),
                full.stream().filter(mark -> mark.placement() == PrintedFingeringDigits.Placement.BELOW).toList());
        var erased = gray.clone();
        for (int y = 187; y <= 209; y++) Arrays.fill(erased, y * WIDTH + 134, y * WIDTH + 148, (byte) 255);
        assertEquals(full.stream().filter(mark -> mark.placement() == PrintedFingeringDigits.Placement.ABOVE
                        || mark.noteIndex() == 3).toList(),
                PrintedFingeringDigits.capture(gray, erased, WIDTH, HEIGHT, words, score, events));
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
