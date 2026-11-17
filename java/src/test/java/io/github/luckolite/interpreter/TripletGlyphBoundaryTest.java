// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original curled glyphs and simultaneous voices; no commercial score pixels. */
public class TripletGlyphBoundaryTest {
    private static String[] glyph() {
        var rows =
                new ArrayList<>(
                        List.of(
                                "..#######...",
                                ".##########.",
                                "###......###",
                                "####.....###",
                                "####.....###",
                                "####.....###",
                                ".##.....####",
                                ".......####.",
                                "......####..",
                                "....#####...",
                                "....#####...",
                                "......####..",
                                ".......####.",
                                "##.....####.",
                                "###....####.",
                                "###....####.",
                                "###....####.",
                                ".###....###.",
                                "..########..",
                                "....####...."));
        rows.add(10, "....#####...");
        rows.add(19, "..########..");
        return rows.toArray(new String[0]);
    }

    private static ScoreNoteEvent shortNote(float x, int divisor) {
        return new ScoreNoteEvent(
                0, x, 2, 0, 1, .4f, false, 0, 1, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, divisor);
    }

    private static ScoreNoteEvent quarter(int dots) {
        return new ScoreNoteEvent(
                0, .25f, 0, 0, 1, .46f, false, dots, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1);
    }

    private static List<ScoreNoteEvent> detect(String[] shape, List<ScoreNoteEvent> notes) {
        byte[] gray = new byte[400 * 240];
        Arrays.fill(gray, (byte) 255);
        for (int y = 0; y < shape.length; y++)
            for (int x = 0; x < shape[y].length(); x++)
                if (shape[y].charAt(x) == '#') gray[(145 + y) * 400 + 119 + x] = 0;
        return TripletRhythmDetector.apply(
                notes, List.of(new MeasureRegion(0, 1, .2f, .6f)), gray, 400, 240);
    }

    private static List<ScoreNoteEvent> moving() {
        return List.of(shortNote(.25f, 1), shortNote(.3125f, 1), shortNote(.375f, 1));
    }

    @Test
    public void belowBeamRestAliasRequiresAnIsolatedThreeAtItsActualCenter() throws Exception {
        int width = 400, height = 240;
        var method = WrappedEighthTriplets.class.getDeclaredMethod("detachedTripletThree",
                byte[].class, int.class, int.class, float.class, float.class, float.class);
        method.setAccessible(true);
        byte[] paper = new byte[width * height];
        Arrays.fill(paper, (byte) 255);
        String[] shape = glyph();
        for (int y = 0; y < shape.length; y++)
            for (int x = 0; x < shape[y].length(); x++)
                if (shape[y].charAt(x) == '#') paper[(145 + y) * width + 119 + x] = 0;
        assertNotNull(method.invoke(null, paper, width, height, 124.5f, 155.5f, 16f));
        assertNull(method.invoke(null, paper, width, height, 129.5f, 155.5f, 16f));
        var closedEight = paper.clone();
        for (int y = 2; y < shape.length - 2; y++) {
            closedEight[(145 + y) * width + 119] = 0;
            closedEight[(145 + y) * width + 120] = 0;
        }
        assertNull(method.invoke(null, closedEight, width, height, 124.5f, 155.5f, 16f));
    }

    @Test
    public void aTripletNumeralMustLieUnderTheFirstGroup() throws Exception {
        int width = 400, height = 240;
        var read = WrappedEighthTriplets.class.getDeclaredMethod("firstTripletNumeral",
                byte[].class, int.class, int.class, float.class, float.class, float.class, float.class);
        read.setAccessible(true);
        byte[] paper = new byte[width * height];
        Arrays.fill(paper, (byte) 255);
        String[] three = glyph();
        for (int y = 0; y < three.length; y++)
            for (int x = 0; x < three[y].length(); x++)
                if (three[y].charAt(x) == '#') paper[(145 + y) * width + 119 + x] = 0;
        assertNotNull(read.invoke(null, paper, width, height, 124.5f, 132f, 149f, 16f));
        assertNull(read.invoke(null, paper, width, height, 124.5f, 132f, 155f, 16f));
        assertNull(read.invoke(null, paper, width, height, 132f, 132f, 149f, 16f));
    }

    @Test
    public void partialPixelRowRetainsTheShortLowerOpening() {
        assertTrue(detect(glyph(), moving()).stream().allMatch(n -> n.tupletDivisor() == 3));
    }

    @Test
    public void closedEightStillDoesNotBecomeATriplet() {
        String[] shape = glyph();
        for (int y = 2; y < shape.length - 2; y++) shape[y] = "##" + shape[y].substring(2);
        assertTrue(detect(shape, moving()).stream().allMatch(n -> n.tupletDivisor() == 1));
    }

    @Test
    public void solidUpperLeftStemOfFiveStillDoesNotBecomeATriplet() {
        String[] shape = glyph();
        for (int y = 2; y < 10; y++) shape[y] = "###.........";
        assertTrue(detect(shape, moving()).stream().allMatch(n -> n.tupletDivisor() == 1));
    }

    @Test
    public void flatFootCannotSupplyTheLowerCurveOfAThree() {
        String[] shape = glyph();
        shape[shape.length - 2] = "############";
        shape[shape.length - 1] = "############";
        assertTrue(detect(shape, moving()).stream().allMatch(n -> n.tupletDivisor() == 1));
    }

    @Test
    public void heldQuarterDoesNotChooseTheTripletsDurationScale() {
        var input = new ArrayList<ScoreNoteEvent>();
        input.add(quarter(0));
        input.addAll(moving());
        var notes = detect(glyph(), input);
        assertEquals(1, notes.get(0).tupletDivisor());
        assertEquals(
                1, ScoreNoteTiming.resolvedWrittenDurationBeats(notes.get(0), notes, 4), .0001);
        for (int i = 1; i < 4; i++)
            assertEquals(
                    1.0 / 3,
                    ScoreNoteTiming.resolvedWrittenDurationBeats(notes.get(i), notes, 4),
                    .0001);
    }

    @Test
    public void aDottedHeldQuarterCannotDotTheMovingTriplet() {
        var held = quarter(1);
        var first = shortNote(.25f, 3);
        var notes = List.of(held, first, shortNote(.3125f, 3), shortNote(.375f, 3));
        assertEquals(1.5, ScoreNoteTiming.resolvedWrittenDurationBeats(held, notes, 4), .0001);
        assertEquals(1.0 / 3, ScoreNoteTiming.resolvedWrittenDurationBeats(first, notes, 4), .0001);
    }

    @Test
    public void ordinaryAndTripletEighthsKeepTheirOwnDurations() {
        var ordinary = shortNote(.25f, 1);
        var triplet = shortNote(.25f, 3);
        var notes = List.of(ordinary, triplet, shortNote(.3125f, 3), shortNote(.375f, 3));
        assertEquals(.5, ScoreNoteTiming.resolvedWrittenDurationBeats(ordinary, notes, 4), .0001);
        assertEquals(
                1.0 / 3, ScoreNoteTiming.resolvedWrittenDurationBeats(triplet, notes, 4), .0001);
    }
}
