// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class CompactDottedOctaveTest {
    private static final int WIDTH = 480, HEIGHT = 225;
    private static final List<PlayingTechniqueDetector.Staff> STAFFS =
            List.of(new PlayingTechniqueDetector.Staff(119, 183, 16, 0, 1));

    @Test
    public void compactClosedRailRequiresItsWholeDownwardTurn() {
        var source = engravedLine();
        assertEquals(List.of(0, 1, 1, 0), shifts(source));

        var missingMiddleDash = source.clone();
        ink(missingMiddleDash, 376, 79, 380, 86, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(missingMiddleDash));

        ink(source, 238, 64, 243, 71, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(source));
    }

    @Test
    public void shortEngravedContinuationNeedsBothDownwardDots() {
        var page = engravedNumeral();
        for (int x = 126; x <= 238; x += 8) ink(page, x, 65, x + 5, 70, 0);
        ink(page, 240, 73, 242, 77, 0);
        ink(page, 240, 81, 242, 85, 0);
        assertEquals(List.of(0, 1, 1, 0), shifts(page, 70, 110, 225, 260));
        var singleDot = page.clone();
        ink(singleDot, 239, 80, 243, 86, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(singleDot, 70, 110, 225, 260));
        var reversed = page.clone();
        ink(reversed, 239, 72, 243, 86, 255);
        ink(reversed, 240, 58, 242, 62, 0);
        ink(reversed, 240, 50, 242, 54, 0);
        assertEquals(List.of(0, 0, 0, 0), shifts(reversed, 70, 110, 225, 260));
    }

    @Test
    public void closedEightStartsAtTheFollowingIndependentBeam() {
        assertEquals(List.of(0, 0, 1, 1, 0), shiftsAtBeamBeginning(beamedEight(false)));
    }

    @Test
    public void closedEightDoesNotAdvanceWithoutBothIndependentBeams() {
        assertEquals(List.of(0, 1, 1, 1, 0), shiftsAtBeamBeginning(beamedEight(true)));
    }

    private static byte[] beamedEight(boolean missingLeadingBeam) {
        var page = engravedLine();
        shaftAndHead(page, 58, 115, 174);
        shaftAndHead(page, 92, 98, 158);
        shaftAndHead(page, 124, 131, 190);
        shaftAndHead(page, 157, 115, 175);
        if (!missingLeadingBeam) beam(page, 48, 174, 82, 158);
        beam(page, 114, 190, 147, 175);
        return page;
    }

    private static List<Integer> shiftsAtBeamBeginning(byte[] page) {
        return OctaveMarkDetector.apply(
                        List.of(),
                        STAFFS,
                        List.of(new MeasureRegion(0, 1, 0, 1)),
                        List.of(
                                downNote(58, 115),
                                downNote(92, 98),
                                downNote(124, 131),
                                downNote(157, 115),
                                downNote(419, 152)),
                        page,
                        WIDTH,
                        HEIGHT)
                .stream()
                .map(ScoreNoteEvent::octaveShift)
                .toList();
    }

    private static ScoreNoteEvent downNote(int x, int y) {
        return new ScoreNoteEvent(
                        0, x / (float) WIDTH, Math.round((183 - y) / 8f), 0, 1,
                        y / (float) HEIGHT, false, 0, 1, 2, 0, 1)
                .withStemDirection(-1);
    }

    private static void shaftAndHead(byte[] page, int x, int y, int stemEnd) {
        for (int row = -5; row <= 5; row++) {
            int radius = Math.round(10 * (float) Math.sqrt(1 - row * row / 36f));
            ink(page, x - radius, y + row, x + radius, y + row, 0);
        }
        ink(page, x - 10, y, x - 8, stemEnd, 0);
    }

    private static void beam(byte[] page, int left, int top, int right, int end) {
        for (int x = left; x <= right; x++) {
            int y = Math.round(top + (end - top) * ((x - left) / (float) (right - left)));
            ink(page, x, y - 3, x, y + 2, 0);
        }
    }

    private static List<Integer> shifts(byte[] page) {
        return shifts(page, 70, 110, 353, 419);
    }

    private static List<Integer> shifts(byte[] page, int first, int second, int third, int fourth) {
        return OctaveMarkDetector.apply(
                        List.of(),
                        STAFFS,
                        List.of(new MeasureRegion(0, 1, 0, 1)),
                        List.of(note(first), note(second), note(third), note(fourth)),
                        page,
                        WIDTH,
                        HEIGHT)
                .stream()
                .map(ScoreNoteEvent::octaveShift)
                .toList();
    }

    private static ScoreNoteEvent note(int x) {
        return new ScoreNoteEvent(
                0, x / (float) WIDTH, 2, 0, 1, 152f / HEIGHT, false, 0, 1, 2, 0, 1);
    }

    private static byte[] engravedNumeral() {
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        ink(page, 100, 62, 122, 88, 0);
        ink(page, 105, 66, 118, 72, 255);
        ink(page, 105, 77, 118, 84, 255);
        return page;
    }

    private static byte[] engravedLine() {
        var page = engravedNumeral();
        for (int x = 126; x <= 374; x += 8) ink(page, x, 65, x + 5, 70, 0);
        for (int y : new int[] {73, 81, 89}) ink(page, 377, y, 379, y + 4, 0);
        return page;
    }

    private static void ink(byte[] page, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
