// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class EighthTripletsBeforeSixteenthsTest {
    private static final int WIDTH = 680;
    private static final int HEIGHT = 370;
    private static final int GAP = 16;
    private static final int BEFORE_Y = 73;
    private static final int TARGET_Y = 243;
    private static final int FIRST_X = 61;
    private static final int SPACING = 40;

    @Test
    public void assignsOnlyTheTwoTripletGroupsBeforeWrittenSixteenths() {
        var sheet = fixture(4);
        var timing =
                EighthTripletsBeforeSixteenths.capture(sheet.score(), sheet.ink(), WIDTH, HEIGHT);
        assertEquals(26, timing.notes().size());
        for (int i = 0; i < timing.notes().size(); i++) {
            var original = sheet.score().notes().get(i);
            var expected = i >= 12 && i < 18 ? original.withTupletRatio(3, 2) : original;
            assertEquals("owner " + i, expected, timing.notes().get(i));
        }
        var placements = ScorePlacementMap.resolveWritten(timing.notes(), 4, timing.meterChanges());
        for (int i = 0; i < 14; i++) {
            assertEquals(
                    i < 6 ? i / 3.0 : 2 + (i - 6) / 4.0,
                    placements.get(12 + i).onsetBeats(),
                    .0001);
            assertEquals(i < 6 ? 1.0 / 3 : .25, placements.get(12 + i).durationBeats(), .0001);
        }
    }

    @Test
    public void requiresSeparateTripletGroupsAndOwnedSecondaryBeams() {
        var joined = fixture(4);
        var erased = fixture(4);
        int cut = FIRST_X + 2 * SPACING;
        draw(joined.ink(), cut + 1, TARGET_Y + 55, cut + SPACING - 12, TARGET_Y + 62, 0);
        int firstSixteenth = FIRST_X + 6 * SPACING;
        draw(
                erased.ink(),
                firstSixteenth + 3,
                TARGET_Y + 40,
                firstSixteenth + 21,
                TARGET_Y + 54,
                255);
        assertSame(
                joined.score(),
                EighthTripletsBeforeSixteenths.capture(
                        joined.score(), joined.ink(), WIDTH, HEIGHT));
        assertSame(
                erased.score(),
                EighthTripletsBeforeSixteenths.capture(
                        erased.score(), erased.ink(), WIDTH, HEIGHT));
    }

    @Test
    public void leavesAnOtherwiseIdenticalBarInAnotherMeterUntouched() {
        var sheet = fixture(3);
        assertSame(
                sheet.score(),
                EighthTripletsBeforeSixteenths.capture(sheet.score(), sheet.ink(), WIDTH, HEIGHT));
    }

    private record Fixture(ScorePageInterpretation score, byte[] ink) {}

    private static Fixture fixture(int numerator) {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 12; i++) addNote(ink, notes, 0, i, BEFORE_Y, 1);
        for (int i = 0; i < 14; i++) addNote(ink, notes, 1, i, TARGET_Y, i < 6 ? 1 : 2);
        for (int i = 0; i < 12; i += 3) beam(ink, i, 3, BEFORE_Y, 1);
        for (int i = 0; i < 6; i += 3) beam(ink, i, 3, TARGET_Y, 1);
        for (int i = 6; i < 14; i += 4) beam(ink, i, 4, TARGET_Y, 2);
        var before = new MeasureRegion(34f / WIDTH, 514f / WIDTH, 39f / HEIGHT, 170f / HEIGHT);
        var target = new MeasureRegion(34f / WIDTH, 614f / WIDTH, 209f / HEIGHT, 340f / HEIGHT);
        return new Fixture(
                new ScorePageInterpretation(
                        List.of(before, target),
                        notes,
                        0,
                        List.of(),
                        List.of(),
                        List.of(new ScoreMeterChange(0, numerator, 4))),
                ink);
    }

    private static void addNote(
            byte[] ink, List<ScoreNoteEvent> notes, int measure, int index, int topY, int beams) {
        int x = FIRST_X + index * SPACING;
        int y = topY + (index % 3) * (GAP / 2);
        for (int dy = -6; dy <= 6; dy++)
            for (int dx = -10; dx <= 10; dx++)
                if (dx * dx + 2 * dy * dy <= 100) ink[(y + dy) * WIDTH + x + dx] = 0;
        draw(ink, x - 10, y, x - 8, topY + 62, 0);
        float pos = (x - 34f) / (measure == 0 ? 480f : 580f);
        notes.add(
                new ScoreNoteEvent(
                                measure,
                                pos,
                                10 - index % 3,
                                0,
                                1,
                                y / (float) HEIGHT,
                                false,
                                0,
                                beams,
                                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                                0)
                        .withClef(30)
                        .withStemDirection(-1));
    }

    private static void beam(byte[] ink, int first, int groupSize, int y, int rails) {
        int left = FIRST_X + first * SPACING - 10;
        int right = left + (groupSize - 1) * SPACING + 2;
        draw(ink, left, y + 54, right, y + 62, 0);
        if (rails == 2) draw(ink, left, y + 41, right, y + 49, 0);
    }

    private static void draw(byte[] ink, int left, int top, int right, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
