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
