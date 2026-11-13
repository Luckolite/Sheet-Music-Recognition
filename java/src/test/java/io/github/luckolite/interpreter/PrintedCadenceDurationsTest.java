// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PrintedCadenceDurationsTest {
    private static final int WIDTH = 600;
    private static final int HEIGHT = 220;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final List<PlayingTechniqueDetector.Staff> staffs =
            List.of(new PlayingTechniqueDetector.Staff(60, 124, 16, 0, 1));

    public PrintedCadenceDurationsTest() {
        Arrays.fill(gray, (byte) 255);
        for (int y = 60; y <= 124; y += 16) {
            Arrays.fill(gray, y * WIDTH + 20, y * WIDTH + 580, (byte) 0);
        }
    }

    private void stroke(int x) {
        for (int y = 60; y <= 124; y++) {
            gray[y * WIDTH + x] = 0;
            gray[y * WIDTH + x + 1] = 0;
        }
    }

    private ScoreNoteEvent note(float x, int y) {
        return new ScoreNoteEvent(1, x, 0, 0, 1, y / (float) HEIGHT,
                false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 2);
    }

    private float[] durations(List<ScoreNoteEvent> chord, List<ScoreMeterChange> meters) {
        var measures = List.of(
                new MeasureRegion(30f / WIDTH, 240f / WIDTH, .14f, .7f),
                new MeasureRegion(258f / WIDTH, 322f / WIDTH, .14f, .7f),
                new MeasureRegion(340f / WIDTH, 560f / WIDTH, .14f, .7f));
        var score = new ScorePageInterpretation(measures, chord, 1, List.of(), List.of(), meters);
        float[] beats = {4, 4, 4};
        PrintedCadenceDurations.apply(
                PrintedCadenceDurations.capture(score, gray, WIDTH, HEIGHT, staffs), beats);
        return beats;
    }

    @Test
    public void oneWrittenChordFillsAShortCadenceBeforeDoubleBar() {
        stroke(327);
        stroke(334);
        assertArrayEquals(new float[] {4, 2, 4},
                durations(List.of(note(.43f, 74), note(.44f, 102)), List.of()), 0);
    }

    @Test
    public void singleOrdinaryBarDoesNotShortenTheClock() {
        stroke(330);
        assertArrayEquals(new float[] {4, 4, 4},
                durations(List.of(note(.43f, 74)), List.of(new ScoreMeterChange(3, 4, 4))), 0);
    }

    @Test
    public void printedMeterAtNextBarAlsoClosesTheSection() {
        stroke(330);
        assertArrayEquals(new float[] {4, 2, 4},
                durations(List.of(note(.43f, 74)), List.of(new ScoreMeterChange(2, 4, 4))), 0);
    }

    @Test
    public void separateAttacksAndAdditionalGlyphsAbstain() {
        stroke(327);
        stroke(334);
        assertArrayEquals(new float[] {4, 4, 4},
                durations(List.of(note(.31f, 74), note(.6f, 102)), List.of()), 0);
        for (int y = 83; y <= 100; y++) gray[y * WIDTH + 309] = 0;
        assertArrayEquals(new float[] {4, 4, 4},
                durations(List.of(note(.35f, 74)), List.of()), 0);
    }

    @Test
    public void writtenAccidentalCanExtendPastTheOrdinaryChordExclusion() {
        stroke(327);
        stroke(334);
        for (int y = 82; y <= 100; y++) gray[y * WIDTH + 270] = 0;
        var natural = new ScoreNoteEvent(1, .49f, 0, 0, 1, 74f / HEIGHT,
                false, 0, 0, ScoreNoteEvent.ACCIDENTAL_NATURAL, 2);
        assertArrayEquals(new float[] {4, 2, 4}, durations(List.of(natural), List.of()), 0);
        assertArrayEquals(new float[] {4, 4, 4}, durations(List.of(note(.49f, 74)), List.of()), 0);
    }

    private void localTerminalBar(boolean hasCompanion, boolean interrupted) {
        Arrays.fill(gray, (byte) 255);
        for (int y = 54; y <= 118; y += 16)
            Arrays.fill(gray, y * WIDTH + 20, y * WIDTH + 570, (byte) 0);
        for (int y = 54; y <= 118; y++) {
            Arrays.fill(gray, y * WIDTH + 329, y * WIDTH + 338, (byte) 0);
            if (hasCompanion && (!interrupted || y < 70 || y > 86))
                Arrays.fill(gray, y * WIDTH + 322, y * WIDTH + 325, (byte) 0);
        }
    }

    private float[] terminalDurations() {
        var measures = List.of(
                new MeasureRegion(30f / WIDTH, 240f / WIDTH, .14f, .7f),
                new MeasureRegion(258f / WIDTH, 338f / WIDTH, .14f, .7f));
        var score = new ScorePageInterpretation(
                measures, List.of(note(.38f, 94)), 1, List.of(), List.of(), List.of());
        float[] beats = {4, 4};
        PrintedCadenceDurations.apply(
                PrintedCadenceDurations.capture(score, gray, WIDTH, HEIGHT, staffs), beats);
        return beats;
    }

    @Test
    public void localRailsSeparateAThickFinalBarFromInteriorInk() {
        localTerminalBar(true, false);
        assertArrayEquals(new float[] {4, 2}, terminalDurations(), 0);
    }

    @Test
    public void interruptedTerminalStrokeNeedsAnIntactCompanion() {
        localTerminalBar(true, true);
        assertArrayEquals(new float[] {4, 2}, terminalDurations(), 0);
        localTerminalBar(false, false);
        assertArrayEquals(new float[] {4, 4}, terminalDurations(), 0);
    }
}
