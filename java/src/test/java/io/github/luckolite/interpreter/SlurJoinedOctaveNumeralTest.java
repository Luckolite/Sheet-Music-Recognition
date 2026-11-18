// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SlurJoinedOctaveNumeralTest {
    private static final int WIDTH = 420, HEIGHT = 212;
    private static final float GAP = 16;

    @Test
    public void lowerRimOnSlurKeepsBothCountersAndNeedsDottedReturn() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        paint(ink, 67, 81, 36, 52, 0);
        paint(ink, 60, 76, 49, 65, 0);
        paint(ink, 71, 78, 40, 47, 255);
        paint(ink, 63, 72, 53, 61, 255);
        paint(ink, 39, 77, 64, 65, 0);
        paint(ink, 25, 97, 66, 67, 0);
        paint(ink, 18, 111, 68, 69, 0);
        for (int x = 86; x <= 318; x += 9) paint(ink, x, x + 4, 42, 44, 0);

        var open = ink.clone();
        paint(ink, 315, 315, 43, 53, 0);
        assertTrue(hasOctave(ink));
        assertFalse(hasOctave(open));
        var openCounter = ink.clone();
        paint(openCounter, 73, 76, 36, 40, 255);
        assertFalse(hasOctave(openCounter));
    }

    private static boolean hasOctave(byte[] ink) {
        var staff = new PlayingTechniqueDetector.Staff(123, 187, GAP, 0, 1);
        return OctaveMarkDetector.printedWords(ink, WIDTH, HEIGHT, List.of(staff)).stream()
                .anyMatch(w -> w.text().equals("8va"));
    }

    private static void paint(byte[] ink, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
