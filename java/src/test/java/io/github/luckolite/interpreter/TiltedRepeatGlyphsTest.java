// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TiltedRepeatGlyphsTest {
    private static final int WIDTH = 1800, HEIGHT = 360, GAP = 16;
    private static final int DOT_X = 1540, INNER_X = 1550, OUTER_X = 1556;
    private static final int STAFF_TOP = 130;
    private static final List<PlayingTechniqueDetector.Staff> STAFF = List.of(
            new PlayingTechniqueDetector.Staff(STAFF_TOP, STAFF_TOP + 4 * GAP, GAP, 0, 1));
    private static final List<MeasureRegion> MEASURES = List.of(
            new MeasureRegion(.13f, .50f, .26f, .65f),
            new MeasureRegion(.51f, INNER_X / (float) WIDTH, .26f, .65f));

    private static byte[] page(boolean coherentStaff) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        // The repeat lies more than a gap from the flat seed at the far end.
        for (int x = 135; x <= OUTER_X + 7; x++) {
            int top = coherentStaff ? staffTop(x) : x < 1400 ? STAFF_TOP : staffTop(DOT_X);
            for (int line = 0; line < 5; line++) {
                int y = top + line * GAP;
                gray[y * WIDTH + x] = 0;
                gray[(y + 1) * WIDTH + x] = 0;
            }
        }
        int railTop = coherentStaff ? staffTop(INNER_X) : staffTop(DOT_X);
        for (int y = railTop; y <= railTop + 4 * GAP; y++) {
            Arrays.fill(gray, y * WIDTH + INNER_X, y * WIDTH + INNER_X + 2, (byte) 0);
            Arrays.fill(gray, y * WIDTH + OUTER_X, y * WIDTH + OUTER_X + 7, (byte) 0);
        }
        for (int center : new int[] {staffTop(DOT_X) + 24, staffTop(DOT_X) + 40})
            for (int dy = -3; dy <= 3; dy++)
                for (int dx = -3; dx <= 3; dx++)
                    if (dx * dx + dy * dy <= 9) gray[(center + dy) * WIDTH + DOT_X + dx] = 0;
        return gray;
    }

    private static int staffTop(int x) {
        return STAFF_TOP - Math.round((x - WIDTH * .5f) * .0285f);
    }

    @Test
    public void outgoingRepeatFollowsTheProvedStaffAtItsColumn() {
        var marks = PrintedRepeatGlyphs.detect(page(true), WIDTH, HEIGHT, STAFF, MEASURES);
        assertEquals(1, marks.size());
        assertEquals(2, marks.get(0).boundary());
        assertTrue(marks.get(0).outgoing());
    }

    @Test
    public void shortOffsetRailsCannotOverrideTheTrackedStaff() {
        assertTrue(PrintedRepeatGlyphs.detect(page(false), WIDTH, HEIGHT, STAFF, MEASURES).isEmpty());
    }
}
