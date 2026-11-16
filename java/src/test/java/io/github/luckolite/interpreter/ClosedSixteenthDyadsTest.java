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

    @Test
    public void twoIndependentBeamGroupsVetoOneCrossCutTripletClaim() {
        var ink = closedSixteenthPage();
        var stock = crossCutFixture();
        var after =
                ClosedSixteenthDyads.apply(
                        stock, new ScoreMeterChange(0, 4, 4), ink, ink, WIDTH, HEIGHT);
        for (int i = 0; i < stock.notes().size(); i++)
            assertEquals(stock.notes().get(i).withTupletRatio(1, 1), after.notes().get(i));
        assertSame(stock, ClosedSixteenthDyads.apply(stock, null, ink, ink, WIDTH, HEIGHT));
    }

    @Test
    public void lostRailOrActualCrossCutBracketRetainsTriplet() {
        var stock = crossCutFixture();
        var ink = closedSixteenthPage();
        var meter = new ScoreMeterChange(0, 4, 4);
        var broken = ink.clone();
        draw(broken, 123, 133, PRIMARY - 2, PRIMARY + 2, (byte) 255);
        assertSame(stock, ClosedSixteenthDyads.apply(stock, meter, ink, broken, WIDTH, HEIGHT));
        var bracket = ink.clone();
        draw(bracket, 187, 211, 362, 362, (byte) 0);
        draw(bracket, 232, 258, 362, 362, (byte) 0);
        draw(bracket, 187, 187, 355, 361, (byte) 0);
        draw(bracket, 258, 258, 355, 361, (byte) 0);
        assertSame(stock, ClosedSixteenthDyads.apply(stock, meter, bracket, ink, WIDTH, HEIGHT));
    }

    @Test
    public void correctedChordsPublishIdentitiesForAllFourDoubleBeams() {
        var stock = crossCutFixture();
        var ink = closedSixteenthPage();
        var meter = new ScoreMeterChange(0, 4, 4);
        var withoutPage = ClosedSixteenthDyads.apply(stock, meter, ink, ink, WIDTH, HEIGHT);
        var after = ClosedSixteenthDyads.apply(stock, meter, ink, ink, WIDTH, HEIGHT, 8);
        assertEquals(withoutPage.notes(), after.notes());
        assertEquals(List.of(), withoutPage.beamSource().groups());
        var source = after.beamSource();
        assertEquals(4, source.groups().size());
        for (int group = 0; group < 4; group++) {
            var beams = source.groups().get(group);
            assertEquals(List.of(3, 3, 3), beams.bridges());
            assertEquals(List.of(), beams.hooks());
            assertEquals(4, beams.columns().size());
            for (int column = 0; column < 4; column++) {
                int owner = group * 8 + column * 2;
                assertEquals(
                        List.of(
                                new ScoreBeamSource.Anchor(8, owner),
                                new ScoreBeamSource.Anchor(8, owner + 1)),
                        beams.columns().get(column).members());
            }
        }
        var duplicateOrigins = new ArrayList<>(source.noteOrigins());
        duplicateOrigins.set(31, duplicateOrigins.get(30));
        var occupied = new ScoreBeamSource(List.of(), List.of(), duplicateOrigins);
        var blocked =
                ClosedSixteenthDyads.apply(
                        stock.withBeamSource(occupied), meter, ink, ink, WIDTH, HEIGHT, 8);
        assertEquals(withoutPage.notes(), blocked.notes());
        assertEquals(occupied, blocked.beamSource());
    }

    private static ScorePageInterpretation crossCutFixture() {
        int right = 656;
        var region =
                new MeasureRegion(
                        (float) LEFT / WIDTH, (float) right / WIDTH,
                        (float) (STAFF_BOTTOM - 98) / HEIGHT, (float) (STAFF_BOTTOM + 33) / HEIGHT);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 16; slot++) {
            int x = FIRST + slot * 34;
            for (int y : new int[] {UPPER, LOWER}) {
                int ratio = slot >= 2 && slot <= 4 ? 3 : 1;
                notes.add(
                        new ScoreNoteEvent(
                                0,
                                (float) (x - LEFT) / (right - LEFT),
                                (STAFF_BOTTOM - y) / 8,
                                0,
                                1,
                                (float) y / HEIGHT,
                                false,
                                0,
                                2,
                                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                                0,
                                ratio,
                                0,
                                0,
                                30,
                                false,
                                0,
                                false,
                                0,
                                0,
                                ratio == 3 ? 2 : 1,
                                -1,
                                ScoreNoteEvent.Kind.PITCHED,
                                ScoreNoteEvent.VisualSize.FULL,
                                0,
                                null));
            }
        }
        return new ScorePageInterpretation(List.of(region), notes);
    }

    private static byte[] closedSixteenthPage() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = STAFF_BOTTOM - 4 * GAP; y <= STAFF_BOTTOM; y += GAP)
            draw(ink, 26, WIDTH - 25, y, y, (byte) 0);
        for (int x : new int[] {LEFT, 656})
            draw(ink, x - 1, x + 1, STAFF_BOTTOM - 4 * GAP, STAFF_BOTTOM, (byte) 0);
        for (int slot = 0; slot < 16; slot++) {
            int x = FIRST + slot * 34;
            draw(ink, x - 9, x - 7, UPPER, PRIMARY + 2, (byte) 0);
            draw(ink, x - 9, x + 7, UPPER - 4, UPPER + 4, (byte) 0);
            draw(ink, x - 9, x + 7, LOWER - 4, LOWER + 4, (byte) 0);
        }
        for (int start : new int[] {0, 4, 8, 12}) {
            int first = FIRST + start * 34 - 9;
            int last = first + 3 * 34 + 2;
            draw(ink, first, last, PRIMARY - 2, PRIMARY + 2, (byte) 0);
            draw(ink, first, last, SECONDARY - 2, SECONDARY + 2, (byte) 0);
        }
        return ink;
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
