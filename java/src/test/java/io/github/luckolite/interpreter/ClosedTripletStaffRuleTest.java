// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ClosedTripletStaffRuleTest {
    private static final int WIDTH = 620, HEIGHT = 230;
    private static final int LEFT = 38, RIGHT = 585, TOP = 46, BOTTOM = 177;
    private static final int[] HEAD_ROWS = {112, 96, 80, 56, 80, 96, 112, 96, 80, 56, 80, 96};
    private static final int[] SHAFT_TIPS = {
        160, 153, 146, 123, 135, 147, 179, 172, 165, 124, 134, 144
    };

    @Test
    public void ignoresTheCrossedStaffRuleButRetainsARealBeamBridge() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 80; y <= 144; y += 16) ink(gray, LEFT, RIGHT, y, y + (y == 128 ? 3 : 1));
        ink(gray, LEFT, LEFT + 2, 80, 144);
        ink(gray, RIGHT - 1, RIGHT + 1, 80, 144);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < HEAD_ROWS.length; i++) {
            int x = headX(i), y = HEAD_ROWS[i], shaft = shaftX(i);
            ink(gray, x - 7, x + 5, y - 4, y + 4);
            ink(gray, shaft - 1, shaft + 1, y, SHAFT_TIPS[i]);
            if (i % 3 != 2) rail(gray, i);
            notes.add(
                    new ScoreNoteEvent(
                            0,
                            (x - LEFT) / (float) (RIGHT - LEFT),
                            (144 - y) / 8,
                            0,
                            1,
                            y / (float) HEIGHT,
                            false,
                            0,
                            1,
                            2,
                            0,
                            1,
                            0,
                            0,
                            30,
                            false,
                            0,
                            false,
                            0,
                            0,
                            1,
                            -1,
                            ScoreNoteEvent.Kind.PITCHED,
                            ScoreNoteEvent.VisualSize.FULL,
                            0,
                            null));
        }
        var measures =
                List.of(
                        new MeasureRegion(
                                LEFT / (float) WIDTH,
                                RIGHT / (float) WIDTH,
                                TOP / (float) HEIGHT,
                                BOTTOM / (float) HEIGHT));
        var meter = List.of(new ScoreMeterChange(0, 4, 4));
        var qualified =
                WrappedEighthTriplets.closeQuarterBeatBars(
                        notes, List.of(), measures, meter, gray, WIDTH, HEIGHT);
        assertEquals(12, qualified.stream().filter(n -> n.tupletDivisor() == 3).count());

        rail(gray, 2);
        assertSame(
                notes,
                WrappedEighthTriplets.closeQuarterBeatBars(
                        notes, List.of(), measures, meter, gray, WIDTH, HEIGHT));
    }

    private static int headX(int slot) {
        return 67 + slot * 43;
    }

    private static int shaftX(int slot) {
        return headX(slot) - 9;
    }

    private static void rail(byte[] gray, int slot) {
        int a = shaftX(slot), b = shaftX(slot + 1);
        for (int x = a; x <= b; x++) {
            float t = (x - a) / (float) (b - a);
            int y = Math.round(SHAFT_TIPS[slot] * (1 - t) + SHAFT_TIPS[slot + 1] * t);
            ink(gray, x, x, y - 6, y);
        }
    }

    private static void ink(byte[] gray, int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
