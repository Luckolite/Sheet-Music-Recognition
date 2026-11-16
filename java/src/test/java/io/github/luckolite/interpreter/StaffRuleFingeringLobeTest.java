// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class StaffRuleFingeringLobeTest {
    private static final int WIDTH = 200;
    private static final int HEIGHT = 202;
    private static final MeasureRegion BAR = new MeasureRegion(.03f, .92f, .34f, .88f);

    @Test
    public void staffCrossingNumeralDoesNotBecomeASecondPitchedHead() {
        assertEquals(3, read(true, true, false));
    }

    @Test
    public void otherInkCannotDisplaceAHeadWithoutTheWholeNumeralAndUpperGroup() {
        assertEquals(4, read(false, true, false));
        assertEquals(4, read(true, false, false));
    }

    @Test
    public void anIndependentLowerShaftKeepsItsPitchedEvent() {
        assertEquals(4, read(true, true, true));
    }

    @Test
    public void wholeValueAliasRequiresTheCurledDigitAndIndependentBeamInBothPlanes() {
        var glyph = wholeValueFixture(false);
        var heldWhole = wholeValueFixture(true);
        var missingStroke = glyph.clone();
        rectangle(missingStroke, 90, 156, 94, 162, 255);
        var missingMusicShaft = glyph.clone();
        rectangle(missingMusicShaft, 82, 76, 90, 110, 255);

        assertEquals(1, wholeValueCount(glyph, glyph, "3"));
        assertEquals(2, wholeValueCount(glyph, glyph, "8"));
        for (var rival : List.of(heldWhole, missingStroke, missingMusicShaft)) {
            assertEquals(2, wholeValueCount(glyph, rival, "3"));
            assertEquals(2, wholeValueCount(rival, glyph, "3"));
        }
    }

    private static int wholeValueCount(byte[] normalized, byte[] raw, String text) {
        var alias =
                new ScoreNoteEvent(
                        0,
                        (96f / WIDTH - BAR.left()) / (BAR.right() - BAR.left()),
                        -1,
                        0,
                        1,
                        160f / HEIGHT,
                        false,
                        0,
                        0,
                        2,
                        4);
        var notes = List.of(event(94, 60, 12, 2, -1), alias);
        var words =
                List.of(
                        new PlayingTechniqueDetector.Word(
                                text, 83f / WIDTH, 139f / HEIGHT, 109f / WIDTH, 171f / HEIGHT));
        var staffs = List.of(new PlayingTechniqueDetector.Staff(86, 150, 16, 0, 1));
        return FingeringAnnotationFilter.removeWholeFingeringAliases(
                        words, staffs, List.of(BAR), notes, normalized, raw, WIDTH, HEIGHT)
                .size();
    }

    private static byte[] wholeValueFixture(boolean hollowWhole) {
        byte[] paper = new byte[WIDTH * HEIGHT];
        Arrays.fill(paper, (byte) 255);
        for (int line = 86; line <= 150; line += 16) rectangle(paper, 12, line, 186, line + 1, 0);
        rectangle(paper, 84, 56, 102, 64, 0);
        rectangle(paper, 84, 60, 87, 121, 0);
        rectangle(paper, 84, 116, 142, 121, 0);
        if (hollowWhole) {
            rectangle(paper, 81, 154, 111, 164, 0);
            rectangle(paper, 91, 157, 102, 162, 255);
            return paper;
        }
        rectangle(paper, 92, 145, 102, 152, 0);
        rectangle(paper, 100, 153, 103, 162, 0);
        rectangle(paper, 91, 156, 94, 162, 0);
        rectangle(paper, 93, 162, 102, 164, 0);
        return paper;
    }

    private static int read(boolean upperLobe, boolean sharedBeam, boolean lowerStem) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        rectangle(gray, 9, 149, 190, 152, 0);
        if (upperLobe) {
            rectangle(gray, 87, 139, 98, 148, 0);
            rectangle(gray, 90, 141, 93, 145, 255);
        }
        rectangle(gray, 86, 153, 99, 158, 0);
        rectangle(gray, 91, 154, 94, 155, 255);
        if (lowerStem) rectangle(gray, 84, 155, 87, 191, 0);

        int[] columns = {59, 91, 123};
        int[] rows = {45, 53, 61};
        for (int i = 0; i < columns.length; i++) {
            rectangle(gray, columns[i] - 10, rows[i] - 4, columns[i] + 10, rows[i] + 4, 0);
            rectangle(gray, columns[i] - 10, rows[i], columns[i] - 7, 115, 0);
        }
        rectangle(gray, 49, 109, 115, 115, 0);
        rectangle(gray, 49, 97, 115, 103, 0);
        if (!sharedBeam) rectangle(gray, 98, 96, 109, 119, 255);
        var notes = List.of(event(59, 45, 14, 2, -1), event(91,53,13,2,-1),
                event(123,61,12,2,-1), event(93,155,0,3,0));
        var words = List.of(new PlayingTechniqueDetector.Word(
                "3", 81f / WIDTH, 132f / HEIGHT, 105f / WIDTH, 164f / HEIGHT));
        var staves = List.of(new PlayingTechniqueDetector.Staff(86,150,16,0,1));
        return FingeringAnnotationFilter.apply(words, staves, List.of(BAR), notes, gray, WIDTH, HEIGHT).size();
    }

    private static ScoreNoteEvent event(int x, int y, int step, int beams, int direction) {
        float position = (x / (float) WIDTH - BAR.left()) / (BAR.right() - BAR.left());
        return new ScoreNoteEvent(0, position, step, 0, 1, y / (float) HEIGHT,
                false, 0, beams, 2, 0).withStemDirection(direction);
    }

    private static void rectangle(byte[] gray, int l, int t, int r, int b, int color) {
        for (int row = t; row <= b; row++) {
            Arrays.fill(gray, row * WIDTH + l, row * WIDTH + r + 1, (byte) color);
        }
    }
}
