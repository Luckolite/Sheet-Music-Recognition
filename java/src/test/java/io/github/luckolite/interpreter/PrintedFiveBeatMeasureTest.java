// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PrintedFiveBeatMeasureTest {
    private static final int WIDTH = 1030, HEIGHT = 390;
    private static final int GAP = 16, TOP = 120, BOTTOM = 184;
    private static final int LEFT = 100, RIGHT = 957;
    private static final MeasureRegion BEFORE = region(23, 82, TOP, BOTTOM);
    private static final MeasureRegion BAR = region(LEFT, RIGHT, TOP, BOTTOM);
    private static final MeasureRegion NEXT = region(LEFT, RIGHT, 312, 376);

    @Test
    public void acceptsOnlyFourCompleteGroupsThenAnUnflaggedQuarter() {
        var plate = new Plate();
        assertTrue(proves(plate.gray, plate.gray, plate.notes));

        var singleRule = plate.gray.clone();
        for (int y = TOP; y <= BOTTOM; y++) {
            if ((y - TOP) % GAP <= 1) continue;
            Arrays.fill(singleRule, y * WIDTH + 960, y * WIDTH + 964, (byte) 255);
        }
        assertFalse(proves(singleRule, plate.gray, plate.notes));
        assertFalse(proves(plate.gray, singleRule, plate.notes));

        var brokenRun = plate.gray.clone();
        for (int y = 110; y <= 119; y++)
            Arrays.fill(brokenRun, y * WIDTH + 158, y * WIDTH + 195, (byte) 255);
        assertFalse(proves(plate.gray, brokenRun, plate.notes));

        var flagged = plate.gray.clone();
        draw(flagged, 904, 922, 104, 113);
        assertFalse(proves(plate.gray, flagged, plate.notes));

        var absentShaft = plate.gray.clone();
        for (int y = 100; y <= 162; y++) {
            if (y >= TOP && (y - TOP) % GAP <= 1) continue;
            Arrays.fill(absentShaft, y * WIDTH + 901, y * WIDTH + 909, (byte) 255);
        }
        assertFalse(proves(absentShaft, plate.gray, plate.notes));
        assertFalse(proves(plate.gray, plate.gray, plate.notes.subList(0, 16)));
    }

    private static boolean proves(byte[] normal, byte[] original, List<ScoreNoteEvent> notes) {
        return PrintedFiveBeatMeasure.proves(BEFORE, BAR, NEXT, notes, normal, original, WIDTH, HEIGHT);
    }

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH,
                (top - 2.1f * GAP) / HEIGHT, Math.min(1, (bottom + 2.1f * GAP) / HEIGHT));
    }

    private static final class Plate {
        final byte[] gray = new byte[WIDTH * HEIGHT];
        final List<ScoreNoteEvent> notes = new ArrayList<>();
        Plate() {
            Arrays.fill(gray, (byte) 255);
            for (int y = TOP; y <= BOTTOM; y += GAP) draw(gray, 30, 963, y, y + 1);
            draw(gray, 89, 91, TOP, BOTTOM);
            draw(gray, 950, 952, TOP, BOTTOM);
            draw(gray, 960, 962, TOP, BOTTOM);
            for (int attack = 0; attack < 17; attack++) {
                int x = attack == 16 ? 895 : 138 + 46 * attack;
                int end = attack == 16 ? 100 : 96;
                draw(gray, x + 8, x + 11, end, BOTTOM);
                for (int dy = -7; dy <= 7; dy++) {
                    int ink = Math.round(11 * (float) Math.sqrt(1 - dy*dy / 64f));
                    draw(gray, x - ink, x + ink, BOTTOM + dy, BOTTOM + dy);
                }
                notes.add(head(x, attack == 16));
            }
            for (int start = 0; start < 16; start += 4) {
                int a = 146 + 46 * start, b = a + 46 * 3 + 3;
                draw(gray, a, b, 96, 104);
                draw(gray, a, b, 111, 119);
            }
        }
    }

    private static ScoreNoteEvent head(int x, boolean terminal) {
        float position = (x - LEFT) / (float) (RIGHT - LEFT);
        return new ScoreNoteEvent(1, position, 0, 0, 1, BOTTOM / (float) HEIGHT,
                false, 0, terminal ? 0 : 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                terminal ? 1 : 0, 1, 0, 0, 30, false, 0, false, 0, 0, 1, 1,
                ScoreNoteEvent.Kind.PITCHED);
    }

    private static void draw(byte[] gray, int x, int right, int y, int bottom) {
        for (int row = y; row <= bottom; row++)
            Arrays.fill(gray, row * WIDTH + x, row * WIDTH + right + 1, (byte) 0);
    }
}
