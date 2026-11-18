// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ShortStaffEdgeOctaveTest {
    private static final int WIDTH = 1240, HEIGHT = 290, GAP = 16;
    private static final int STAFF_TOP = 152, LAST_DOT = 1152;
    private static final int GLYPH_LEFT = 994, GLYPH_RIGHT = 1024;
    private static final int RIBBON_Y = 82;
    private static final List<PlayingTechniqueDetector.Staff> STAVES =
            List.of(new PlayingTechniqueDetector.Staff(STAFF_TOP, STAFF_TOP + 4 * GAP, GAP, 0, 1));

    @Test
    public void shortOpenOctaveLineEndsAtTheIndependentStaffEdge() {
        var paper = engraving();
        assertEquals(List.of(0, 1, 1, 0), shifts(paper));

        var missingRule = paper.clone();
        paint(missingRule, LAST_DOT + 1, LAST_DOT + 4, STAFF_TOP, STAFF_TOP + 4 * GAP, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(missingRule));
    }

    @Test
    public void staffEdgeCannotAuthorizeAnIncompleteNumeralOrLine() {
        var missingCounter = engraving();
        paint(missingCounter, GLYPH_LEFT, GLYPH_LEFT + 5, 80, 84, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(missingCounter));

        var missingDots = engraving();
        paint(missingDots, GLYPH_RIGHT + 15, LAST_DOT - 27, RIBBON_Y - 2, RIBBON_Y + 6, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(missingDots));
    }

    private static List<Integer> shifts(byte[] paper) {
        var notes = List.of(note(951), note(1034), note(1136), note(1173));
        return OctaveMarkDetector.apply(
                        List.of(),
                        STAVES,
                        List.of(new MeasureRegion(0, 1, 0, 1)),
                        notes,
                        paper,
                        WIDTH,
                        HEIGHT)
                .stream()
                .map(ScoreNoteEvent::octaveShift)
                .toList();
    }

    private static ScoreNoteEvent note(int x) {
        return new ScoreNoteEvent(
                0, x / (float) WIDTH, 2, 0, 1, 184f / HEIGHT, false, 0, 1, 2, 0, 1);
    }

    private static byte[] engraving() {
        var paper = new byte[WIDTH * HEIGHT];
        Arrays.fill(paper, (byte) 255);
        paint(paper, GLYPH_LEFT, GLYPH_RIGHT, 74, 103, 0);
        paint(paper, GLYPH_LEFT + 4, GLYPH_RIGHT - 4, 78, 84, 255);
        paint(paper, GLYPH_LEFT + 4, GLYPH_RIGHT - 4, 91, 99, 255);
        for (int x = LAST_DOT; x > GLYPH_RIGHT + 6; x -= 9)
            paint(paper, x - 4, x, RIBBON_Y, RIBBON_Y + 3, 0);
        int edge = LAST_DOT + 3;
        for (int y = STAFF_TOP; y <= STAFF_TOP + 4 * GAP; y += GAP) paint(paper, 45, edge, y, y, 0);
        paint(paper, edge - 2, edge, STAFF_TOP, STAFF_TOP + 4 * GAP, 0);
        return paper;
    }

    private static void paint(byte[] paper, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(paper, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
