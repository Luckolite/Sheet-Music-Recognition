// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class CompactTerminalQuarterTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 230;
    private static final int TOP = 54;
    private static final int GAP = 14;
    private static final int BOTTOM = TOP + 4 * GAP;
    private static final int LEFT = 190;
    private static final int RIGHT = 241;
    private static final int HEAD = 205;
    private static final int OPENING = 182;
    private static final int CLOSING = 236;
    private static final int SECOND_CLOSING = 243;
    private static final MeasureRegion BAR = region(LEFT, RIGHT, 25, 140);
    private static final MeasureRegion NEXT_SYSTEM = region(27, 175, 157, 224);

    @Test
    public void shortBarWithFiveRailsContainsOneQuarterChord() {
        assertTrue(qualifies(raster(true,true,true), NEXT_SYSTEM, twoHeads()));
    }

    @Test
    public void requiresOpeningClosingAndCompleteStaff() {
        assertFalse(qualifies(raster(false,true,true), NEXT_SYSTEM, twoHeads()));
        assertFalse(qualifies(raster(true,false,true), NEXT_SYSTEM, twoHeads()));
        assertFalse(qualifies(raster(true,true,false), NEXT_SYSTEM, twoHeads()));
    }

    @Test
    public void requiresSeparateSystemAndTwoHeadsWithCommonShaft() {
        var gray = raster(true,true,true);
        assertFalse(qualifies(gray, region(257,297,25,140), twoHeads()));
        assertFalse(qualifies(gray, NEXT_SYSTEM, List.of(head(HEAD,0,1))));
        assertFalse(qualifies(gray, NEXT_SYSTEM, List.of(head(HEAD,0,1),head(HEAD + 1,-2,-1))));
    }

    private static boolean qualifies(byte[] gray, MeasureRegion next, List<ScoreNoteEvent> notes) {
        return PrintedCadenceDurations.compactTerminalQuarter(gray, WIDTH, HEIGHT, BAR, next, notes);
    }

    private static List<ScoreNoteEvent> twoHeads() {
        return List.of(head(HEAD,0,1),head(HEAD+1,-2,1));
    }

    private static ScoreNoteEvent head(int x, int step, int stem) {
        return new ScoreNoteEvent(0, (x - LEFT) / (float) (RIGHT - LEFT), step, 0, 1,
                (BOTTOM - step * GAP * .5f) / HEIGHT, false, 0, 0,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1, 1, 0, 0, 30, false, 0, false, 0, 0, 1,
                stem, ScoreNoteEvent.Kind.PITCHED);
    }

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH,
                top / (float) HEIGHT, bottom / (float) HEIGHT);
    }

    private static byte[] raster(boolean opening, boolean closingSecond, boolean allStaffRules) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += GAP) {
            int to = !allStaffRules && y == TOP ? HEAD : SECOND_CLOSING + 2;
            Arrays.fill(gray, y * WIDTH + 21, y * WIDTH + to, (byte) 0);
        }
        if (opening) stroke(gray, OPENING);
        stroke(gray, CLOSING);
        if (closingSecond) stroke(gray, SECOND_CLOSING);
        return gray;
    }

    private static void stroke(byte[] gray, int x) {
        for (int y = TOP; y <= BOTTOM; y++) {
            Arrays.fill(gray, y * WIDTH + x - 1, y * WIDTH + x + 2, (byte) 0);
        }
    }
}
