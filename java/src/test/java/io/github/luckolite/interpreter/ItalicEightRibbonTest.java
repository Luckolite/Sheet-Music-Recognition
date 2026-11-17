// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ItalicEightRibbonTest {
    private static final int WIDTH = 600;
    private static final int HEIGHT = 182;
    private static final int GAP = 16;
    private static final int STAFF_TOP = 104;
    private static final int LAST_DOT = 444;
    private static final List<PlayingTechniqueDetector.Staff> STAFFS =
            List.of(new PlayingTechniqueDetector.Staff(STAFF_TOP, STAFF_TOP + 4 * GAP, GAP, 0, 1));

    @Test
    public void widerEightFollowsTheClosedDottedRibbon() {
        byte[] page = engraving(false);
        staff(page, LAST_DOT + 62);
        assertEquals(List.of(), words(page));
        paint(page, LAST_DOT, LAST_DOT + 3, 53, 56, 0);
        paint(page, LAST_DOT, LAST_DOT + 3, 60, 64, 0);
        assertEquals(List.of("8va"), words(page));
        paint(page, 161, 244, 43, 52, 255);
        assertEquals(List.of(), words(page));
    }

    @Test
    public void repairedLowerLoopRequiresThePhysicalOpenStaffEdge() {
        byte[] page = engraving(true);
        staff(page, LAST_DOT + 2);
        assertEquals(List.of("8va"), words(page));
        paint(page, LAST_DOT - 2, LAST_DOT + 4, STAFF_TOP, STAFF_TOP + 4 * GAP, 255);
        assertEquals(List.of(), words(page));
    }

    private static List<String> words(byte[] page) {
        return OctaveMarkDetector.printedWords(page, WIDTH, HEIGHT, STAFFS)
                .stream().map(PlayingTechniqueDetector.Word::text).toList();
    }

    private static byte[] engraving(boolean broken) {
        byte[] page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        paint(page, 53, 68, 38, 55, 0);
        paint(page, 40, 58, 51, 66, 0);
        paint(page, 58, 66, 42, 49, 255);
        paint(page, 44, 53, 55, 62, 255);
        if (broken) paint(page, 40, 45, 62, 64, 255);
        for (int x = LAST_DOT; x >= 75; x -= 9) paint(page, x - 4, x, 46, 49, 0);
        return page;
    }

    private static void staff(byte[] page, int right) {
        for (int y = STAFF_TOP; y <= STAFF_TOP + 4 * GAP; y += GAP)
            paint(page, 31, right, y, y, 0);
        paint(page, right - 1, right, STAFF_TOP, STAFF_TOP + 4 * GAP, 0);
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
