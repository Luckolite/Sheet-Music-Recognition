// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class CrossedDottedOctaveSpanTest {
    private static final int W = 406, H = 200;
    private static final int OCTAVE_Y = 74;
    private static final List<PlayingTechniqueDetector.Staff> STAFFS =
            List.of(new PlayingTechniqueDetector.Staff(119, 183, 16, 0, 1));

    @Test
    public void connectedSlurWithTwoDotRunsAndDownwardHookOwnsOnlyItsSpan() {
        var source = engraving();
        assertEquals(List.of(0, 1, 1, 0), shifts(source));
        var dots = OctaveMarkDetector.printedDashInk(source, W, H, STAFFS);
        assertTrue(dots.get(OCTAVE_Y * W + 65));
        assertTrue(dots.get(OCTAVE_Y * W + 165));
        assertFalse(dots.get(68 * W + 102));
    }

    @Test
    public void anUnconnectedGapOrAbsentTerminalHookCannotJoinTheDottedRuns() {
        var absentSlur = engraving();
        box(absentSlur, 97, 60, 114, 70, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(absentSlur));
        var absentHook = engraving();
        box(absentHook, 267, 79, 282, 108, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(absentHook));
    }

    @Test
    public void aSlurEndingAfterTheRailCanOwnEightRemainingDots() {
        var raw = endingSlurEngraving();
        assertEquals(List.of(0, 1, 1, 0), shifts(raw, 79, 212, 246));
        var dots = OctaveMarkDetector.printedDashInk(raw, W, H, STAFFS);
        assertTrue(dots.get(OCTAVE_Y * W + 65));
        assertTrue(dots.get(OCTAVE_Y * W + 210));
        assertFalse(dots.get(68 * W + 121));
    }

    @Test
    public void shortCrossingNeedsTheEndingSlurAndItsTerminalHook() {
        var erasedSlur = endingSlurEngraving();
        box(erasedSlur, 125, 65, 155, 83, 255);
        assertEquals(0, (int) shifts(erasedSlur, 79, 212, 246).get(2));
        var erasedHook = endingSlurEngraving();
        box(erasedHook, 219, 80, 231, 107, 255);
        assertEquals(0, (int) shifts(erasedHook, 79, 212, 246).get(2));
    }

    @Test
    public void onlyTheOwnedAccidentalCarryStopsWhenThePrintedOctaveEnds() {
        var explicit = note(79).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_SHARP);
        var within = note(240).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_SHARP);
        var after = note(306).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_SHARP);
        var renewed = note(335).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_SHARP);
        var notes = List.of(explicit, within, after, renewed);
        var carries = List.of(new OctaveAccidentalCarry(explicit, within),
                new OctaveAccidentalCarry(explicit, after));
        var page = engraving();
        var region = List.of(new MeasureRegion(0, 1, 0, 1));
        var result = OctaveMarkDetector.apply(List.of(), STAFFS, region, notes, page, W, H,
                new java.util.ArrayList<>(), carries);
        assertEquals(List.of(1, 1, 0, 0), result.stream().map(ScoreNoteEvent::octaveShift).toList());
        assertEquals(List.of(1, 1, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1),
                result.stream().map(ScoreNoteEvent::writtenAccidental).toList());

        box(page, 267, 79, 282, 108, 255);
        assertEquals(notes, OctaveMarkDetector.apply(List.of(), STAFFS, region, notes, page, W, H,
                new java.util.ArrayList<>(), carries));
    }

    @Test
    public void pairedWrittenOctaveKeepsBothAccidentalsAtTheOctaveBoundary() {
        var firstLow = carryNote(.2f, 3);
        var firstHigh = carryNote(.3f, 10);
        var nextLow = carryNote(.6f, 3);
        var nextHigh = carryNote(.6015f, 10);
        var source = List.of(firstLow, firstHigh, nextLow, nextHigh);
        var assigned = new java.util.ArrayList<>(source);
        assigned.set(0, firstLow.withOctaveShift(1));
        assigned.set(1, firstHigh.withOctaveShift(1));
        var carries = List.of(new OctaveAccidentalCarry(firstLow, nextLow),
                new OctaveAccidentalCarry(firstHigh, nextHigh));
        var ranges = new boolean[] {true, true, false, false};
        OctaveAccidentalCarry.clearAtRangeBoundary(source, assigned, ranges, carries);
        assertEquals(List.of(1, 1, 1, 1), assigned.stream().map(ScoreNoteEvent::writtenAccidental).toList());
        OctaveAccidentalCarry.clearAtRangeBoundary(source, assigned, ranges, List.of(carries.get(0)));
        assertEquals(List.of(1, 1, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1),
                assigned.stream().map(ScoreNoteEvent::writtenAccidental).toList());
    }

    @Test
    public void priorLocalNaturalReturnsAfterAnInterveningFlatInTheOctavePassage() {
        var earlyNatural = carryNote(.12f, 10).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_NATURAL);
        var octaveFlat = carryNote(.35f, 10).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FLAT);
        var inheritedFlat = carryNote(.75f, 10).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FLAT);
        var sources = List.of(earlyNatural, octaveFlat, inheritedFlat);
        var assigned = new java.util.ArrayList<>(sources);
        assigned.set(1, octaveFlat.withOctaveShift(1));
        OctaveAccidentalCarry.clearAtRangeBoundary(sources, assigned,
                new boolean[] {false, true, false}, List.of(new OctaveAccidentalCarry(octaveFlat, inheritedFlat)));
        assertEquals(List.of(0, -1, 0), assigned.stream().map(ScoreNoteEvent::writtenAccidental).toList());
    }

    private static ScoreNoteEvent carryNote(float x, int step) {
        return new ScoreNoteEvent(0, x, step, 0, 1, .70f - step * .02f, false)
                .withStemDirection(-1).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_SHARP);
    }

    private static byte[] endingSlurEngraving() {
        var raw = new byte[W * H];
        Arrays.fill(raw, (byte) 255);
        box(raw, 41, 70, 61, 92, 0);
        box(raw, 47, 73, 55, 78, 255);
        box(raw, 46, 83, 55, 88, 255);
        for (int x = 65; x <= 119; x += 9) box(raw, x, OCTAVE_Y, x + 5, OCTAVE_Y + 4, 0);
        for (int y = 62; y <= 81; y++) {
            int x = 110 + Math.round((y - 62) * 1.8f);
            int thickness = y >= 79 ? 3 : 6;
            box(raw, x, y, x + thickness - 1, y, 0);
        }
        box(raw, 127, 74, 154, 78, 0);
        for (int x = 156; x <= 219; x += 9) box(raw, x, OCTAVE_Y, x + 5, OCTAVE_Y + 4, 0);
        for (int y : new int[] {83, 91, 99}) box(raw, 222, y, 225, y + 3, 0);
        return raw;
    }



    @Test
    public void broadShallowSlurAndJoinedTerminalStillBoundTheRibbon() throws Exception {
        var page = shallowSlurEngraving();
        assertTrue(crossedClosure(page));
        assertEquals(List.of(0, 1, 1, 0), shifts(page));
        box(page, 270, 80, 300, 111, 255);
        assertFalse(crossedClosure(page));

        page = shallowSlurEngraving();
        box(page, 103, 60, 151, 77, 255);
        assertFalse(crossedClosure(page));
    }

    private static boolean crossedClosure(byte[] source) throws Exception {
        var detector = OctaveMarkDetector.class;
        var box = Class.forName(detector.getName() + "$InkBox");
        var constructor = box.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var numeral = constructor.newInstance(41, 70, 61, 94, 0);
        var method = detector.getDeclaredMethod(
                "dottedRibbonAcrossSlur", byte[].class, int.class, int.class, box, float.class);
        method.setAccessible(true);
        return method.invoke(null, source, W, H, numeral, 16f) != null;
    }

    private static byte[] shallowSlurEngraving() {
        var raw = new byte[W * H];
        Arrays.fill(raw, (byte) 255);
        box(raw, 41, 70, 61, 94, 0);
        box(raw, 47, 73, 55, 79, 255);
        box(raw, 46, 84, 55, 90, 255);
        for (int x = 65; x <= 101; x += 9) box(raw, x, 75, x + 5, 79, 0);
        for (int y = 67; y <= 83; y++) {
            int x = 92 + Math.round((y - 67) * 2.8f);
            box(raw, x, y, x + (y <= 71 ? 13 : 5), y, 0);
        }
        box(raw, 112, 75, 135, 79, 0);
        for (int x = 138; x <= 273; x += 9) box(raw, x, 75, x + 5, 79, 0);
        box(raw, 276, 78, 279, 84, 0);
        box(raw, 276, 88, 279, 92, 0);
        box(raw, 276, 96, 279, 100, 0);
        return raw;
    }

    private static List<Integer> shifts(byte[] raw) {
        return shifts(raw, 79, 240, 306);
    }

    private static List<Integer> shifts(byte[] raw, int first, int last, int after) {
        var notes = List.of(note(20), note(first), note(last), note(after));
        return OctaveMarkDetector.apply(
                        List.of(), STAFFS, List.of(new MeasureRegion(0, 1, 0, 1)), notes, raw, W, H)
                .stream()
                .map(ScoreNoteEvent::octaveShift)
                .toList();
    }

    private static ScoreNoteEvent note(int x) {
        return new ScoreNoteEvent(0, x / (float) W, 2, 0, 1, 152f / H, false, 0, 1, 2, 0, 1);
    }

    private static byte[] engraving() {
        var raw = new byte[W * H];
        Arrays.fill(raw, (byte) 255);

        box(raw, 41, 70, 61, 92, 0);
        box(raw, 47, 73, 55, 78, 255);
        box(raw, 46, 83, 55, 88, 255);
        for (int x = 65; x <= 95; x += 10) box(raw, x, OCTAVE_Y, x + 5, OCTAVE_Y + 4, 0);

        for (int y = 64; y <= 96; y++) {
            int x = 94 + Math.round((y - 64) * 1.9f);
            box(raw, x, y, x + 4, y, 0);
        }
        box(raw, 104, 74, 131, 78, 0);

        for (int x = 135; x <= 275; x += 10) box(raw, x, OCTAVE_Y, x + 5, OCTAVE_Y + 4, 0);
        for (int y : new int[] {83, 91, 99}) box(raw, 277, y, 280, y + 3, 0);
        return raw;
    }

    private static void box(byte[] raw, int l, int t, int r, int b, int v) {
        for (int y = t; y <= b; y++) Arrays.fill(raw, y * W + l, y * W + r + 1, (byte) v);
    }
}
