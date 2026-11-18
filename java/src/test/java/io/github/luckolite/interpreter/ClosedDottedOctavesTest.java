// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Procedurally prints octave shafts, two rails, and independent inward hooks. */
public class ClosedDottedOctavesTest {
    private static final int WIDTH = 1150, HEIGHT = 430;
    private static final int LEFT = 125, RIGHT = 783, STAFF_BOTTOM = 296;
    private static final int FIRST = 170, SPACING = 39, LOWER = 274, UPPER = 218;
    private static final int SEPARATOR = 793;
    private static final int[] NOTES_NEEDING_REPAIR = {0, 1, 8, 9, 10, 11};

    @Test
    public void literalDotAndHookOwnershipRepairsOnlyTheIncorrectOctaves() {
        var score = score();
        var notes = decode(score, image(), 4).notes();
        assertEquals(score.notes().size(), notes.size());
        for (int i = 0; i < notes.size(); i++) {
            int beam = i / 2 % 2 == 0 ? 2 : 3;
            assertEquals(beam, notes.get(i).beamCount());
            assertEquals(3 - beam, notes.get(i).augmentationDots());
            int index = i;
            if (Arrays.stream(NOTES_NEEDING_REPAIR).noneMatch(value -> value == index))
                assertSame(score.notes().get(i), notes.get(i));
        }
    }

    @Test
    public void meterAndPhysicallyPrintedBarlineAreRequired() {
        var score = score();
        assertSame(score, decode(score, image(), 3));
        var noBarline = image();
        for (int y = STAFF_BOTTOM - 64; y <= STAFF_BOTTOM; y++) {
            if ((STAFF_BOTTOM - y) % 16 == 0) continue;
            paint(noBarline, SEPARATOR, SEPARATOR + 2, y, y, (byte) 255);
        }
        assertSame(score, decode(score, noBarline, 4));
    }

    @Test
    public void aCompetingDotOrContinuousThirdRailPreservesTheIncomingRhythm() {
        var score = score();
        var extraDot = image();
        circle(extraDot, FIRST + 30, UPPER - 4);
        assertTrue("second dot", score == decode(score, extraDot, 4));
        var thirdRail = image();
        paint(thirdRail, FIRST - 10, FIRST + 3 * SPACING - 8, 314, 323, (byte) 0);
        assertTrue("continuous third rail", score == decode(score, thirdRail, 4));
    }

    @Test
    public void aMissingTertiaryHookCannotBeInferredFromNeighbors() {
        var score = score();
        var missing = image();
        int shaft = FIRST + 5 * SPACING - 9;
        paint(missing, shaft - 11, shaft - 2, 313, 325, (byte) 255);
        assertSame(score, decode(score, missing, 4));
    }

    @Test
    public void absorbedRailFragmentRequiresSharedQuarterShaftAndDottedOwnersDoubleRail() {
        var score = score(true);
        var repaired = decodeRibbon(score, image());
        assertEquals(32, repaired.notes().size());
        for (int i = 0; i < repaired.notes().size(); i++) {
            var note = repaired.notes().get(i);
            assertEquals(i / 2 % 2 == 0 ? 2 : 3, note.beamCount());
            assertEquals(0, note.unbeamedDurationBeats(), 0);
        }
        var seam = image();
        paint(seam, FIRST + 4 * SPACING + 4, FIRST + 4 * SPACING + 20, 326, 352, (byte) 255);
        assertSame(score, decodeRibbon(score, seam));
        var cut = image();
        int shaft = FIRST + 5 * SPACING;
        paint(cut, shaft - 12, shaft - 6, 249, 263, (byte) 255);
        assertSame(score, decodeRibbon(score, cut));
    }

    @Test
    public void ribbonBeamSourceRequiresEveryHookAndRemapsTheRetainedHeads() {
        var input = score(true);
        var meter = new ScoreMeterChange(0, 4, 4);
        var result =
                ClosedDottedSixteenths.applyClosedOctaveFragments(
                        input, meter, image(), WIDTH, HEIGHT, 7);
        assertEquals(32, result.notes().size());
        assertEquals(4, result.beamSource().groups().size());
        for (var group : result.beamSource().resolve().groups()) {
            assertEquals(List.of(3, 3, 3), group.source().bridges());
            assertEquals(
                    List.of(
                            new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD),
                            new ScoreBeamSource.Hook(3, 3, ScoreBeamSource.Direction.BACKWARD)),
                    group.engravingHooks());
            for (var c : group.columns()) {
                assertEquals(2, c.owners().size());
                assertTrue(c.usable());
            }
        }

        var withoutHook = image();
        int shaft = FIRST + 15 * SPACING - 9;
        paint(withoutHook, shaft - 13, shaft - 2, 312, 325, (byte) 255);
        var repaired =
                ClosedDottedSixteenths.applyClosedOctaveFragments(
                        input, meter, withoutHook, WIDTH, HEIGHT, 7);
        assertEquals(result.notes(), repaired.notes());
        assertTrue(repaired.beamSource().groups().isEmpty());

        var continuous = image();
        int groupStart = FIRST + 12 * SPACING;
        paint(continuous, groupStart - 10, shaft + 1, 314, 323, (byte) 0);
        var competing =
                ClosedDottedSixteenths.applyClosedOctaveFragments(
                        input, meter, continuous, WIDTH, HEIGHT, 7);
        assertEquals(result.notes(), competing.notes());
        assertTrue(competing.beamSource().groups().isEmpty());

        var occupied =
                ScoreBeamSource.capture(
                        7,
                        input.notes().size(),
                        List.of(),
                        List.of(
                                new ScoreBeamSource.Column(
                                        List.of(new ScoreBeamSource.Anchor(7, 0)))));
        var prior =
                ClosedDottedSixteenths.applyClosedOctaveFragments(
                        input.withBeamSource(occupied), meter, image(), WIDTH, HEIGHT, 7);
        assertTrue(prior.beamSource().groups().isEmpty());
        assertEquals(occupied.detachedColumns(), prior.beamSource().detachedColumns());
    }

    @Test
    public void aFusedOpeningBridgeStillRequiresEveryIndependentHookAndAReversedOwner() {
        var paper = image();
        int firstShaft = FIRST - 10;
        int secondShaft = FIRST + SPACING - 10;
        paint(paper, firstShaft, secondShaft, 335, 342, (byte) 0);
        var attacks = new ArrayList<ClosedOctaveBeamSource.Attack>();
        for (int i = 0; i < 12; i++) {
            int x = FIRST + i * SPACING;
            int upper = UPPER + (i == 1 ? 12 : 0);
            attacks.add(
                    new ClosedOctaveBeamSource.Attack(
                            2 * i, 2 * i + 1, x, upper, x, upper + LOWER - UPPER));
        }
        var fragment = new ClosedOctaveBeamSource.Fragment(secondShaft - 13, 338);
        assertTrue("fused but attached", provesTripleClock(paper, attacks, fragment));

        var missing = paper.clone();
        paint(missing, secondShaft - 13, secondShaft - 3, 311, 325, (byte) 255);
        assertFalse("printed hook is required", provesTripleClock(missing, attacks, fragment));

        var cut = paper.clone();
        paint(cut, secondShaft, secondShaft + 2, 248, 267, (byte) 255);
        assertFalse(
                "the reverse shaft must have a genuine octave owner",
                provesTripleClock(cut, attacks, fragment));

        int nextShaft = secondShaft + SPACING;
        var secondFused = paper.clone();
        paint(secondFused, secondShaft, nextShaft, 335, 342, (byte) 0);
        assertFalse(
                "the adjacent pair of rails must remain separated",
                provesTripleClock(secondFused, attacks, fragment));
    }

    @Test
    public void closingDyadRibbonRequiresItsOwnAnchorsAndPrintedGap() {
        var attacks = new ArrayList<ClosedOctaveBeamSource.Attack>();
        for (int i = 11; i < 16; i++) {
            int x = FIRST + i * SPACING;
            attacks.add(new ClosedOctaveBeamSource.Attack(2 * i, 2 * i + 1, x, UPPER, x, LOWER));
        }
        var previous = attacks.get(0);
        var ending = attacks.subList(1, 5);
        var start = ScoreBeamSource.empty(32);
        var page = image();
        var proved =
                ClosedOctaveBeamSource.captureClosingDottedDyads(
                        start, 7, page, WIDTH, HEIGHT, 16, previous, ending);
        assertEquals(1, proved.groups().size());
        var beam = proved.resolve().groups().get(0);
        assertEquals(List.of(3, 3, 3), beam.source().bridges());
        assertEquals(
                List.of(
                        new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD),
                        new ScoreBeamSource.Hook(3, 3, ScoreBeamSource.Direction.BACKWARD)),
                beam.engravingHooks());
        assertEquals(List.of(30, 31), beam.columns().get(beam.columns().size() - 1).owners());

        int shaft = FIRST + 15 * SPACING - 9;
        var missing = page.clone();
        paint(missing, shaft - 13, shaft - 2, 312, 325, (byte) 255);
        assertSame(
                start,
                ClosedOctaveBeamSource.captureClosingDottedDyads(
                        start, 7, missing, WIDTH, HEIGHT, 16, previous, ending));

        var continuous = page.clone();
        paint(continuous, FIRST + 11 * SPACING - 10, FIRST + 12 * SPACING - 8, 342, 349, (byte) 0);
        assertSame(
                start,
                ClosedOctaveBeamSource.captureClosingDottedDyads(
                        start, 7, continuous, WIDTH, HEIGHT, 16, previous, ending));

        var reserved =
                ScoreBeamSource.capture(
                        7,
                        32,
                        List.of(),
                        List.of(
                                new ScoreBeamSource.Column(
                                        List.of(new ScoreBeamSource.Anchor(7, 31)))));
        assertSame(
                reserved,
                ClosedOctaveBeamSource.captureClosingDottedDyads(
                        reserved, 7, page, WIDTH, HEIGHT, 16, previous, ending));
    }

    @Test
    public void separatedDottedGroupsKeepHooksOnlyWhereTheRawSourceProvesThem() {
        var source = ScoreBeamSource.empty(32);
        var attacks = new ArrayList<ClosedOctaveBeamSource.Attack>();
        for (int i = 0; i < 16; i++) {
            int x = FIRST + i * SPACING;
            int upper = UPPER + (i == 1 ? 12 : 0);
            attacks.add(new ClosedOctaveBeamSource.Attack(
                    2 * i, 2 * i + 1, x, upper, x, upper + LOWER - UPPER));
        }
        var raw = image();
        var result = ClosedOctaveBeamSource.captureSeparateDottedOctaveGroups(
                source, 0, raw, raw, WIDTH, HEIGHT, 16, attacks);
        assertEquals(4, result.groups().size());
        assertEquals(
                List.of(
                        new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD),
                        new ScoreBeamSource.Hook(3, 3, ScoreBeamSource.Direction.BACKWARD)),
                result.groups().get(0).hooks());

        var erased = raw.clone();
        int second = FIRST + SPACING - 9;
        paint(erased, second - 13, second - 2, 312, 325, (byte) 255);
        assertEquals(result.groups().subList(1, 4),
                ClosedOctaveBeamSource.captureSeparateDottedOctaveGroups(
                        source, 0, raw, erased, WIDTH, HEIGHT, 16, attacks).groups());

        var joined = raw.clone();
        paint(joined, FIRST + 3 * SPACING - 10, FIRST + 4 * SPACING - 8,
                342, 349, (byte) 0);
        assertSame(source, ClosedOctaveBeamSource.captureSeparateDottedOctaveGroups(
                source, 0, raw, joined, WIDTH, HEIGHT, 16, attacks));
    }

    private static boolean provesTripleClock(
            byte[] paper,
            List<ClosedOctaveBeamSource.Attack> attacks,
            ClosedOctaveBeamSource.Fragment fragment) {
        return ClosedOctaveBeamSource.provesTripleClock(
                paper, WIDTH, HEIGHT, 16, attacks, fragment);
    }

    private static ScorePageInterpretation decodeRibbon(
            ScorePageInterpretation score, byte[] gray) {
        return ClosedDottedSixteenths.applyClosedOctaveFragments(
                score, new ScoreMeterChange(0, 4, 4), gray, WIDTH, HEIGHT);
    }

    private static ScorePageInterpretation decode(
            ScorePageInterpretation score, byte[] gray, int meter) {
        return ClosedDottedSixteenths.applyClosedOctaves(
                score, new ScoreMeterChange(0, meter, 4), gray, WIDTH, HEIGHT);
    }

    private static ScorePageInterpretation score() {
        return score(false);
    }

    private static ScorePageInterpretation score(boolean ribbonAlias) {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 16; i++) {
            int beam = i % 2 == 0 ? 2 : 3;
            int dots = 3 - beam;
            if (!ribbonAlias) {
                if (i == 0) beam = 3;
                if (i == 0 || i == 4) dots = 2;
                if (i == 5) beam = 2;
            } else if (i == 4) {
                beam = 1;
            }
            int top = UPPER + (i == 1 ? 12 : 0);
            for (int y : new int[] {top, top + LOWER - UPPER}) {
                boolean quarter = ribbonAlias && i == 5 && y != top;
                notes.add(
                        new ScoreNoteEvent(
                                1,
                                (FIRST + i * SPACING - LEFT) / (float) (RIGHT - LEFT),
                                y == top ? 10 : 3,
                                0,
                                1,
                                y / (float) HEIGHT,
                                false,
                                dots,
                                quarter ? 0 : beam,
                                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                                quarter ? 1 : 0,
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
                                -1,
                                ScoreNoteEvent.Kind.PITCHED,
                                ScoreNoteEvent.VisualSize.FULL,
                                0,
                                null));
            }
        }
        if (ribbonAlias) {
            int fragment = FIRST + 4 * SPACING + 13;
            notes.add(
                    new ScoreNoteEvent(
                            1,
                            (fragment - LEFT) / (float) (RIGHT - LEFT),
                            0,
                            0,
                            1,
                            344f / HEIGHT,
                            false,
                            0,
                            3,
                            ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                            0,
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
                            0,
                            ScoreNoteEvent.Kind.PITCHED,
                            ScoreNoteEvent.VisualSize.FULL,
                            0,
                            null));
        }
        return new ScorePageInterpretation(
                List.of(
                        measure(700, 1080, 20, 151),
                        measure(LEFT, RIGHT, 199, 330),
                        measure(802, 1080, 199, 330)),
                notes);
    }

    private static MeasureRegion measure(int left, int right, int top, int bottom) {
        return new MeasureRegion(
                left / (float) WIDTH,
                right / (float) WIDTH,
                top / (float) HEIGHT,
                bottom / (float) HEIGHT);
    }

    private static byte[] image() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = STAFF_BOTTOM - 64; y <= STAFF_BOTTOM; y += 16)
            paint(gray, 75, 1080, y, y, (byte) 0);
        paint(gray, SEPARATOR, SEPARATOR + 2, STAFF_BOTTOM - 64, STAFF_BOTTOM, (byte) 0);
        for (int i = 0; i < 16; i++) {
            int x = FIRST + i * SPACING;
            int top = UPPER + (i == 1 ? 12 : 0);
            int lower = top + LOWER - UPPER;
            paint(gray, x - 10, x - 8, top, 349, (byte) 0);
            paint(gray, x - 9, x + 8, top - 5, top + 5, (byte) 0);
            paint(gray, x - 9, x + 8, lower - 5, lower + 5, (byte) 0);
            if (i % 2 == 0) {
                circle(gray, x + 20, UPPER - 4);
                circle(gray, x + 20, LOWER - 4);
            } else {
                paint(gray, x - 21, x - 8, 314, 323, (byte) 0);
            }
            if (i % 4 == 0) {
                paint(gray, x - 10, x + 3 * SPACING - 8, 342, 349, (byte) 0);
                paint(gray, x - 10, x + 3 * SPACING - 8, 328, 335, (byte) 0);
            }
        }
        return gray;
    }

    private static void circle(byte[] gray, int x, int y) {
        for (int dy = -3; dy <= 3; dy++)
            for (int dx = -3; dx <= 3; dx++)
                if (dx * dx + dy * dy <= 10) gray[(y + dy) * WIDTH + x + dx] = 0;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, value);
    }
}
