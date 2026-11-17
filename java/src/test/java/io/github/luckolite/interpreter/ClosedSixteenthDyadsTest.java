// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ClosedSixteenthDyadsTest {
    private static final int WIDTH = 970, HEIGHT = 432;
    private static final int LEFT = 81, RIGHT = 849, STAFF_BOTTOM = 286, GAP = 16;
    private static final int FIRST = 120, SPACING = 50, UPPER = 242, LOWER = 258;
    private static final int PRIMARY = 322, SECONDARY = 308;

    @Test
    public void nextOctaveOwnsTheLowerStaccatoInsteadOfADurationDot() {
        assertFollowingStaccato(7);
    }

    @Test
    public void aLedgerEndCannotVetoTheFollowingSixthsStaccato() {
        assertFollowingStaccato(5);
    }

    private static void assertFollowingStaccato(int interval) {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = STAFF_BOTTOM - 4 * GAP; y <= STAFF_BOTTOM; y += GAP)
            draw(ink, 22, WIDTH - 20, y, y + 1, (byte) 0);
        var bar = new MeasureRegion((float) LEFT / WIDTH, (float) RIGHT / WIDTH,
                (STAFF_BOTTOM - 98f) / HEIGHT, (STAFF_BOTTOM + 33f) / HEIGHT);
        var notes = new ArrayList<ScoreNoteEvent>();
        int shift = interval == 5 ? 8 : 0, spacing = interval == 5 ? 37 : 32;
        for (int i = 0; i < 16; i++) {
            int x = FIRST + i * spacing, low = (i % 2 == 0 ? 294 : 278) + shift;
            for (int y : new int[] {low - 8 * interval, low}) {
                draw(ink, x - 8, x + 8, y - 4, y + 4, (byte) 0);
                notes.add(new ScoreNoteEvent(0, (float) (x - LEFT) / (RIGHT - LEFT),
                                (STAFF_BOTTOM - y) / 8, 0, 1, (float) y / HEIGHT,
                                false, i == 0 ? 1 : 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0)
                        .withClef(ScoreNoteEvent.CLEF_TREBLE).withStemDirection(1));
            }
            int shaft = x + 9;
            draw(ink, shaft, shaft + 2, 178, low, (byte) 0);
            if (i % 4 != 3) {
                draw(ink, shaft, shaft + spacing + 2, 178, 183, (byte) 0);
                draw(ink, shaft, shaft + spacing + 2, 191, 196, (byte) 0);
            }
        }
        if (interval == 5) draw(ink, FIRST - 13, FIRST + 16, 300, 304, (byte) 0);
        draw(ink, FIRST + spacing - 2, FIRST + spacing + 2, 296 + shift, 300 + shift, (byte) 0);
        var score = new ScorePageInterpretation(List.of(bar), notes);
        var meter = new ScoreMeterChange(0, 4, 4);
        var after = ClosedSixteenthDyads.apply(score, meter, ink, ink, WIDTH, HEIGHT);
        for (int i = 0; i < 4; i++) {
            assertEquals(0, after.notes().get(i).augmentationDots());
            assertEquals(2, after.notes().get(i).beamCount());
            assertEquals(i < 2 ? 0 : NoteArticulation.STACCATO, after.notes().get(i).articulations());
        }
        var erased = ink.clone();
        draw(erased, FIRST + spacing - 2, FIRST + spacing + 2, 296 + shift, 300 + shift, (byte) 255);
        assertSame(score, ClosedSixteenthDyads.apply(score, meter, ink, erased, WIDTH, HEIGHT));

        var dotted = ink.clone();
        draw(dotted, FIRST + 16 + shift / 2, FIRST + 19 + shift / 2, 292 + shift, 295 + shift, (byte) 0);
        draw(dotted, FIRST + 17 + shift / 2, FIRST + 18 + shift / 2, 291 + shift, 296 + shift, (byte) 0);
        assertSame(score, ClosedSixteenthDyads.apply(score, meter, ink, dotted, WIDTH, HEIGHT));
    }

    @Test
    public void anUpperDotNearASlurNeedsItsOwnClosedComponent() throws Exception {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        var before = upperStaccatoDyad(100, 180, true);
        var next = upperStaccatoDyad(133, 188, false);
        draw(ink, 130, 136, 168, 173, (byte) 0);
        draw(ink, 125, 142, 164, 165, (byte) 0);
        var proof = ClosedSixteenthDyads.class.getDeclaredMethod("followingUpperStaccato",
                ClosedSixteenthDyads.Ink.class, ClosedSixteenthDyads.Dyad.class,
                ClosedSixteenthDyads.Dyad.class, float.class);
        proof.setAccessible(true);
        var source = new ClosedSixteenthDyads.Ink(ink, WIDTH, HEIGHT);
        assertEquals(true, proof.invoke(null, source, before, next, (float) GAP));
        draw(ink, 132, 133, 166, 169, (byte) 0);
        assertEquals(false, proof.invoke(null, source, before, next, (float) GAP));
    }

    private static ClosedSixteenthDyads.Dyad upperStaccatoDyad(int x, int y, boolean dotted) {
        int dots = dotted ? 1 : 0;
        var high = new ScoreNoteEvent(0, .5f, 7, 0, 1, (float) y / HEIGHT, false, dots, 2, 2, 0)
                .withArticulations(NoteArticulation.STACCATO).withStemDirection(-1);
        var low = new ScoreNoteEvent(0, .5f, 0, 0, 1, (float) (y + 56) / HEIGHT, false, dots, 2, 2, 0)
                .withArticulations(NoteArticulation.STACCATO).withStemDirection(-1);
        return new ClosedSixteenthDyads.Dyad(new ClosedSixteenthDyads.Head(0, high, x, y),
                new ClosedSixteenthDyads.Head(1, low, x, y + 56));
    }

    @Test
    public void accidentalSpacingRequiresTwoIntactRailsOnBothPlanes() {
        var meter = new ScoreMeterChange(0, 4, 4);
        var score = crossCutFixture(true, true);
        var ink = closedSixteenthPage(true);
        var normalized = ClosedSixteenthDyads.apply(score, meter, ink, ink, WIDTH, HEIGHT);
        for (int i = 0; i < score.notes().size(); i++)
            assertEquals(score.notes().get(i).withTupletRatio(1, 1), normalized.notes().get(i));

        var noAccidental = crossCutFixture(true, false);
        assertSame(noAccidental, ClosedSixteenthDyads.apply(noAccidental, meter, ink, ink, WIDTH, HEIGHT));
        int middle = (quartetX(5, true) + quartetX(6, true)) / 2 - 8;
        var gap = ink.clone();
        draw(gap, middle - 6, middle + 6, SECONDARY - 2, SECONDARY + 2, (byte) 255);
        assertSame(score, ClosedSixteenthDyads.apply(score, meter, gap, ink, WIDTH, HEIGHT));
        assertSame(score, ClosedSixteenthDyads.apply(score, meter, ink, gap, WIDTH, HEIGHT));

        var three = ink.clone();
        draw(three, quartetX(5, true) - 9, quartetX(6, true) - 7,
                SECONDARY - 16, SECONDARY - 12, (byte) 0);
        assertSame(score, ClosedSixteenthDyads.apply(score, meter, three, ink, WIDTH, HEIGHT));
        assertSame(score, ClosedSixteenthDyads.apply(score, meter, ink, three, WIDTH, HEIGHT));
    }

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

    @Test
    public void opposingQuartetsKeepSixFourStraightAcrossAnInferredTriplet() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        int top = STAFF_BOTTOM - 4 * GAP, nextLeft = RIGHT + 18;
        for (int y = top; y <= STAFF_BOTTOM; y += GAP)
            draw(ink, 22, WIDTH - 20, y, y + 1, (byte) 0);
        draw(ink, RIGHT + 8, RIGHT + 10, top, STAFF_BOTTOM, (byte) 0);
        var region =
                new MeasureRegion(
                        (float) LEFT / WIDTH, (float) RIGHT / WIDTH,
                        (STAFF_BOTTOM - 98f) / HEIGHT, (STAFF_BOTTOM + 33f) / HEIGHT);
        var next = new MeasureRegion((float) nextLeft / WIDTH, .98f, region.top(), region.bottom());
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 24; i++) {
            int x = FIRST + i * 30;
            int y = i < 4 ? 294 - 8 * i : 264 - (i % 4) * 7;
            boolean rising = i < 4;
            int shaft = x + (rising ? 8 : -8);
            draw(ink, x - 8, x + 8, y - 4, y + 4, (byte) 0);
            if (i < 8) draw(ink, shaft, shaft + 2, rising ? 209 : y, rising ? y : 325, (byte) 0);
            var note =
                    new ScoreNoteEvent(
                                    0,
                                    (float) (x - LEFT) / (RIGHT - LEFT),
                                    0,
                                    0,
                                    1,
                                    (float) y / HEIGHT,
                                    false,
                                    0,
                                    2,
                                    ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                                    0)
                            .withStemDirection(rising ? 1 : -1);
            notes.add(i >= 2 && i <= 4 ? note.withTupletRatio(3, 2) : note);
        }
        draw(ink, FIRST + 8, FIRST + 100, 209, 216, (byte) 0);
        draw(ink, FIRST + 8, FIRST + 100, 222, 228, (byte) 0);
        draw(ink, FIRST + 112, FIRST + 204, 318, 325, (byte) 0);
        draw(ink, FIRST + 112, FIRST + 204, 305, 312, (byte) 0);
        var prior =
                new ScorePageInterpretation(
                        List.of(region, next),
                        notes,
                        0,
                        List.of(),
                        List.of(),
                        List.of(new ScoreMeterChange(0, 6, 4)),
                        List.of());
        var actual = ClosedSixteenthDyads.apply(prior, ink, ink, WIDTH, HEIGHT);
        assertEquals(notes.stream().map(n -> n.withTupletRatio(1, 1)).toList(), actual.notes());
        var erased = ink.clone();
        draw(erased, FIRST + 70, FIRST + 80, 204, 234, (byte) 255);
        assertSame(prior, ClosedSixteenthDyads.apply(prior, ink, erased, WIDTH, HEIGHT));
        var rest = new ScoreRestEvent(0, .6f, .65f, .018f, 0, 1, 1.0);
        var trueRest = prior.withRests(List.of(rest));
        assertSame(trueRest, ClosedSixteenthDyads.apply(trueRest, ink, ink, WIDTH, HEIGHT));
    }

    @Test
    public void attachedSecondRailCompletesTwelveOctavesBeforeAQuarter() {
        int right = 573, spacing = 34, lower = 246, upper = 190;
        int primary = 322, secondary = 307;
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        int top = STAFF_BOTTOM - 4 * GAP;
        for (int y = top; y <= STAFF_BOTTOM; y += GAP)
            draw(ink, 20, WIDTH - 20, y, y + 1, (byte) 0);
        draw(ink, LEFT, LEFT + 3, top, STAFF_BOTTOM, (byte) 0);
        draw(ink, right, right + 3, top, STAFF_BOTTOM, (byte) 0);
        for (int first = 0; first < 12; first += 4) {
            int from = FIRST + spacing * first - 8, to = from + spacing * 3;
            draw(ink, from, to, primary - 5, primary, (byte) 0);
            draw(ink, from, to, secondary - 5, secondary, (byte) 0);
        }
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 13; slot++) {
            int x = FIRST + slot * spacing;
            for (int y : new int[] {upper, lower}) {
                draw(ink, x - 8, x + 8, y - 5, y + 5, (byte) 0);
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
                                        slot == 12 ? 0 : slot == 0 ? 1 : 2,
                                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                                        slot == 12 ? 1 : 0)
                                .withClef(ScoreNoteEvent.CLEF_TREBLE)
                                .withStemDirection(-1));
            }
            draw(ink, x - 8, x - 6, upper, slot == 12 ? STAFF_BOTTOM : primary, (byte) 0);
        }
        var bar =
                new MeasureRegion(
                        (float) LEFT / WIDTH,
                        (float) right / WIDTH,
                        (STAFF_BOTTOM - 98f) / HEIGHT,
                        (STAFF_BOTTOM + 33f) / HEIGHT);
        var before =
                new ScorePageInterpretation(
                        List.of(bar), notes, 0, List.of(), List.of(), List.of(), List.of());
        var meter = new ScoreMeterChange(0, 4, 4);
        var repaired = ClosedSixteenthDyads.apply(before, meter, ink, ink, WIDTH, HEIGHT);
        assertEquals(2, repaired.notes().get(0).beamCount());
        assertEquals(2, repaired.notes().get(1).beamCount());
        assertEquals(before.notes().subList(2, 26), repaired.notes().subList(2, 26));

        var singleRail = ink.clone();
        draw(singleRail, FIRST - 4, FIRST + spacing - 12, secondary - 8, secondary + 4, (byte) 255);
        assertSame(
                before, ClosedSixteenthDyads.apply(before, meter, ink, singleRail, WIDTH, HEIGHT));
        assertSame(before, ClosedSixteenthDyads.apply(before, null, ink, ink, WIDTH, HEIGHT));
    }

    @Test
    public void exactOctavesPublishOnlyIndependentlySeparatedBeamGroups() {
        int right = 656;
        int upper = 198, lower = 254, advance = 34;
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = STAFF_BOTTOM - 4 * GAP; y <= STAFF_BOTTOM; y += GAP) {
            draw(ink, LEFT - 10, right + 10, y, y, (byte) 0);
        }
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 16; slot++) {
            int x = FIRST + slot * advance;
            draw(ink, x - 9, x - 7, upper, PRIMARY + 2, (byte) 0);
            for (int y : new int[] {upper, lower}) {
                draw(ink, x - 9, x + 7, y - 4, y + 4, (byte) 0);
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
                                1,
                                0,
                                0,
                                30,
                                false,
                                0,
                                false,
                                0,
                                0,
                                1,
                                -1,
                                ScoreNoteEvent.Kind.PITCHED,
                                ScoreNoteEvent.VisualSize.FULL,
                                0,
                                null));
            }
        }
        for (int start : new int[] {0, 4, 8, 12}) {
            int first = FIRST + start * advance - 9;
            int last = first + 3 * advance + 2;
            draw(ink, first, last, PRIMARY - 2, PRIMARY + 2, (byte) 0);
            draw(ink, first, last, SECONDARY - 2, SECONDARY + 2, (byte) 0);
        }
        var region =
                new MeasureRegion(
                        (float) LEFT / WIDTH, (float) right / WIDTH,
                        (float) (STAFF_BOTTOM - 93) / HEIGHT, (float) (STAFF_BOTTOM + 38) / HEIGHT);
        var input = new ScorePageInterpretation(List.of(region), notes);
        var meter = new ScoreMeterChange(0, 4, 4);
        var exact = ClosedSixteenthDyads.apply(input, meter, ink, ink, WIDTH, HEIGHT, 8);
        assertEquals(input.notes(), exact.notes());
        assertEquals(4, exact.beamSource().groups().size());
        for (var group : exact.beamSource().groups()) {
            assertEquals(List.of(3, 3, 3), group.bridges());
            assertEquals(List.of(), group.hooks());
        }

        var printedBar = ink.clone();
        draw(printedBar, LEFT - 1, LEFT + 1, STAFF_BOTTOM - 4 * GAP, STAFF_BOTTOM, (byte) 0);
        draw(printedBar, right - 1, right + 1, STAFF_BOTTOM - 4 * GAP, STAFF_BOTTOM, (byte) 0);
        var falseTriplet = new ArrayList<>(notes);
        for (int i = 14; i < 20; i++) falseTriplet.set(i, notes.get(i).withTupletRatio(3, 2));
        var wrongClock = new ScorePageInterpretation(List.of(region), falseTriplet);
        var fixedClock =
                ClosedSixteenthDyads.apply(
                        wrongClock, meter, printedBar, printedBar, WIDTH, HEIGHT, 8);
        assertEquals(notes, fixedClock.notes());
        assertEquals(4, fixedClock.beamSource().groups().size());
        var missingRule = printedBar.clone();
        draw(missingRule, right - 1, right + 1, STAFF_BOTTOM - 4 * GAP, STAFF_BOTTOM, (byte) 255);
        assertEquals(
                falseTriplet,
                ClosedSixteenthDyads.apply(
                                wrongClock, meter, printedBar, missingRule, WIDTH, HEIGHT, 8)
                        .notes());

        var enteringRail = ink.clone();
        draw(enteringRail, FIRST - 26, FIRST - 9, PRIMARY - 2, PRIMARY + 2, (byte) 0);
        assertSame(
                input,
                ClosedSixteenthDyads.apply(input, meter, ink, enteringRail, WIDTH, HEIGHT, 8));
        assertSame(
                input,
                ClosedSixteenthDyads.apply(input, meter, enteringRail, ink, WIDTH, HEIGHT, 8));

        var missingRail = ink.clone();
        draw(missingRail, FIRST + 6, FIRST + 15, SECONDARY - 2, SECONDARY + 2, (byte) 255);
        assertSame(
                input,
                ClosedSixteenthDyads.apply(input, meter, ink, missingRail, WIDTH, HEIGHT, 8));
        assertSame(
                input,
                ClosedSixteenthDyads.apply(input, meter, missingRail, ink, WIDTH, HEIGHT, 8));
        var linked = ink.clone();
        draw(
                linked,
                FIRST + 3 * advance - 9,
                FIRST + 4 * advance - 9,
                PRIMARY - 2,
                PRIMARY + 2,
                (byte) 0);
        assertSame(input, ClosedSixteenthDyads.apply(input, meter, ink, linked, WIDTH, HEIGHT, 8));
        assertSame(input, ClosedSixteenthDyads.apply(input, meter, linked, ink, WIDTH, HEIGHT, 8));
    }

    private static ScorePageInterpretation crossCutFixture() {
        return crossCutFixture(false, false);
    }

    private static ScorePageInterpretation crossCutFixture(boolean wideSpacing, boolean writtenSharp) {
        int right = wideSpacing ? 684 : 656;
        int upper = wideSpacing ? STAFF_BOTTOM - 48 : UPPER;
        int lower = wideSpacing ? STAFF_BOTTOM - 8 : LOWER;
        var region =
                new MeasureRegion(
                        (float) LEFT / WIDTH, (float) right / WIDTH,
                        (float) (STAFF_BOTTOM - 98) / HEIGHT, (float) (STAFF_BOTTOM + 33) / HEIGHT);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 16; slot++) {
            int x = quartetX(slot, wideSpacing);
            for (int y : new int[] {upper, lower}) {
                boolean triplet = wideSpacing ? slot <= 2 : slot >= 2 && slot <= 4;
                int ratio = triplet ? 3 : 1;
                int accidental = y == upper && slot == 6 && writtenSharp
                        ? ScoreNoteEvent.ACCIDENTAL_SHARP : ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
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
                                accidental,
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

    private static int quartetX(int slot, boolean wideSpacing) {
        int extension = wideSpacing && slot >= 6 ? 28 : 0;
        return FIRST + slot * 34 + extension;
    }

    private static byte[] closedSixteenthPage() {
        return closedSixteenthPage(false);
    }

    private static byte[] closedSixteenthPage(boolean wideSpacing) {
        int right = wideSpacing ? 684 : 656;
        int upper = wideSpacing ? STAFF_BOTTOM - 48 : UPPER;
        int lower = wideSpacing ? STAFF_BOTTOM - 8 : LOWER;
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = STAFF_BOTTOM - 4 * GAP; y <= STAFF_BOTTOM; y += GAP)
            draw(ink, 26, WIDTH - 25, y, y, (byte) 0);
        for (int x : new int[] {LEFT, right})
            draw(ink, x - 1, x + 1, STAFF_BOTTOM - 4 * GAP, STAFF_BOTTOM, (byte) 0);
        for (int slot = 0; slot < 16; slot++) {
            int x = quartetX(slot, wideSpacing);
            draw(ink, x - 9, x - 7, upper, PRIMARY + 2, (byte) 0);
            draw(ink, x - 9, x + 7, upper - 4, upper + 4, (byte) 0);
            draw(ink, x - 9, x + 7, lower - 4, lower + 4, (byte) 0);
        }
        for (int start : new int[] {0, 4, 8, 12}) {
            int first = quartetX(start, wideSpacing) - 9;
            int last = quartetX(start + 3, wideSpacing) - 7;
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
