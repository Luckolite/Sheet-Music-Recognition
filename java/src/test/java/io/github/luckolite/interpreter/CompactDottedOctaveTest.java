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

    private static List<Integer> shifts(byte[] page) {
        return OctaveMarkDetector.apply(
                        List.of(),
                        STAFFS,
                        List.of(new MeasureRegion(0, 1, 0, 1)),
                        List.of(note(70), note(110), note(353), note(419)),
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

    private static byte[] engravedLine() {
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        ink(page, 100, 62, 122, 88, 0);
        ink(page, 105, 66, 118, 72, 255);
        ink(page, 105, 77, 118, 84, 255);

        for (int x = 126; x <= 374; x += 8) ink(page, x, 65, x + 5, 70, 0);
        for (int y : new int[] {73, 81, 89}) ink(page, 377, y, 379, y + 4, 0);
        return page;
    }

    private static void ink(byte[] page, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
