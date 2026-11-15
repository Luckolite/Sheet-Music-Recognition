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
