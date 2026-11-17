// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class PrintedOctaveFingeringTest {
    private static final int WIDTH = 400;
    private static final int HEIGHT = 540;
    private static final int X = 160;
    private static final MeasureRegion MEASURE = new MeasureRegion(.15f, .85f, 90f / HEIGHT, 234f / HEIGHT);
    private final byte[] original = new byte[WIDTH * HEIGHT];

    public PrintedOctaveFingeringTest() {
        Arrays.fill(original, (byte) 255);
        three();
        rectangle(X - 2, 101, X + 2, 119);
        rectangle(X - 5, 103, X, 105);
        rectangle(X - 5, 117, X + 4, 119);
    }

    @Test
    public void assignsTopDigitToTheUpperAttackAndBottomDigitToTheLowerAttack() {
        assertEquals(
                List.of(
                        new PrintedFingeringDigits.Mark(0, 1, PrintedFingeringDigits.Placement.ABOVE),
                        new PrintedFingeringDigits.Mark(1, 3, PrintedFingeringDigits.Placement.ABOVE)),
                capture(original.clone(), "31", 7, 0));
    }

    @Test
    public void requiresBothOriginalAndNormalizedGlyphsAndCorrectOcr() {
        byte[] erased = original.clone();
        Arrays.fill(erased, 75 * WIDTH, 100 * WIDTH, (byte) 255);
        assertTrue(capture(erased, "31", 7, 0).isEmpty());
        assertTrue(capture(original.clone(), "41", 7, 0).isEmpty());
        assertTrue(capture(original.clone(), "3", 7, 0).isEmpty());
    }

    @Test
    public void rejectsASeparateAttackOrNonOctaveChordUnderTheSamePrintedWord() {
        assertTrue(capture(original.clone(), "31", 7, .25).isEmpty());
        assertTrue(capture(original.clone(), "31", 6, 0).isEmpty());
    }

    @Test
    public void assignsInterSystemStackByInkInsteadOfWordPaddingOrNextRowHead() {
        assertEquals(
                List.of(
                        new PrintedFingeringDigits.Mark(0, 1, PrintedFingeringDigits.Placement.BELOW),
                        new PrintedFingeringDigits.Mark(1, 3, PrintedFingeringDigits.Placement.BELOW)),
                captureBetweenSystems(382));
        assertTrue(captureBetweenSystems(374).isEmpty());
    }

    @Test
    public void admitsTwoDarkDigitsJoinedOnlyByAGrayHairline() {
        original[99 * WIDTH + X] = (byte) 150;
        original[100 * WIDTH + X] = (byte) 150;
        assertEquals(
                List.of(
                        new PrintedFingeringDigits.Mark(0, 1, PrintedFingeringDigits.Placement.ABOVE),
                        new PrintedFingeringDigits.Mark(1, 3, PrintedFingeringDigits.Placement.ABOVE)),
                capture(original.clone(), "31", 7, 0));
        Arrays.fill(original, 99 * WIDTH + X - 2, 99 * WIDTH + X + 3, (byte) 150);
        assertTrue(capture(original.clone(), "31", 7, 0).isEmpty());
    }

    private List<PrintedFingeringDigits.Mark> captureBetweenSystems(int followingTop) {
        byte[] source = new byte[WIDTH * HEIGHT];
        Arrays.fill(source, (byte) 255);
        System.arraycopy(original, 74 * WIDTH, source, 261 * WIDTH, 50 * WIDTH);
        var word = new SheetInterpreter.Word(
                "31", (X - 16f) / WIDTH, 261f / HEIGHT, (X + 16f) / WIDTH, 311f / HEIGHT);
        var following = new MeasureRegion(.15f, .85f, followingTop / (float) HEIGHT, 514f / HEIGHT);
        var next = new ScoreNoteEvent(
                1, (X / (float) WIDTH - following.left()) / (following.right() - following.left()),
                7, 0, 1, 397f / HEIGHT, false).withStemDirection(-1);
        var score = new ScorePageInterpretation(
                List.of(MEASURE, following), List.of(note(213, 0), note(150, 7), next));
        List<Map<String, Object>> events = List.of(
                Map.of("x", X, "y", 213, "startBeat", 0),
                Map.of("x", X, "y", 150, "startBeat", 0),
                Map.of("x", X, "y", 397, "startBeat", 4));
        return PrintedFingeringDigits.capture(
                source, source.clone(), WIDTH, HEIGHT, List.of(word), score, events);
    }

    private List<PrintedFingeringDigits.Mark> capture(
            byte[] normalized, String text, int upperStep, double upperOnset) {
        var word = new SheetInterpreter.Word(
                text, (X - 16f) / WIDTH, 74f / HEIGHT, (X + 16f) / WIDTH, 124f / HEIGHT);
        var score = new ScorePageInterpretation(
                List.of(MEASURE), List.of(note(213, 0), note(150, upperStep)));
        List<Map<String, Object>> events = List.of(
                Map.of("x", X, "y", 213, "startBeat", 0),
                Map.of("x", X, "y", 150, "startBeat", upperOnset));
        return PrintedFingeringDigits.capture(
                original, normalized, WIDTH, HEIGHT, List.of(word), score, events);
    }

    private static ScoreNoteEvent note(int y, int step) {
        return new ScoreNoteEvent(
                0, (X / (float) WIDTH - MEASURE.left()) / (MEASURE.right() - MEASURE.left()),
                step, 0, 1, y / (float) HEIGHT, false).withStemDirection(-1);
    }

    private void three() {
        int[][] rows = {
            {3, 8}, {1, 10}, {0, 11}, {0, 3, 7, 12}, {0, 2, 8, 12}, {0, 2, 8, 12},
            {8, 12}, {8, 12}, {7, 12}, {4, 10}, {4, 10}, {7, 11}, {8, 12}, {8, 12},
            {0, 2, 8, 12}, {0, 2, 8, 12}, {0, 2, 8, 12}, {0, 2, 8, 12},
            {0, 4, 7, 12}, {0, 11}, {2, 9}, {3, 8}
        };
        for (int row = 0; row < rows.length; row++)
            for (int i = 0; i < rows[row].length; i += 2)
                rectangle(X - 6 + rows[row][i], 77 + row, X - 6 + rows[row][i + 1], 77 + row);
    }

    private void rectangle(int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(original, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
