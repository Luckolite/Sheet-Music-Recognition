// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OwnedTertiaryBeamletsTest {
    private static final int WIDTH = 690;
    private static final int HEIGHT = 205;
    private static final int ESTIMATED_GAP = 16;
    private static final int STAFF_TOP = 54;
    private static final int LEFT = 36;
    private static final int RIGHT = 650;
    private static final int STAFF_BOTTOM = 124;
    private static final int UPPER_HEAD = 89;
    private static final int LOWER_HEAD = 124;
    private static final int BEAM_TIP = 166;

    @Test
    public void restoresOnlyUndercountedHooksOnTheirOwnDyads() {
        assertEquals(List.of(2, 3, 6, 7), recover(true, true));
    }

    @Test
    public void requiresBothAnAttachedHookAndATwoStrokeClose() {
        assertEquals("detached third beamlet", List.of(), recover(false, true));
        assertEquals("single closing stroke", List.of(), recover(true, false));
    }

    private static List<Integer> recover(boolean attachFirstHook, boolean doubleClose) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        // Five printed lines establish a 17.5-pixel gap independently of the note estimate.
        for (int y : new int[] {STAFF_TOP, 72, 89, 107, STAFF_BOTTOM}) {
            ink(gray, LEFT, y, RIGHT + 4, y);
        }
        ink(gray, RIGHT - 4, STAFF_TOP, RIGHT - 3, STAFF_BOTTOM);
        if (doubleClose) {
            ink(gray, RIGHT + 2, STAFF_TOP, RIGHT + 4, STAFF_BOTTOM);
        }

        var heads = new ArrayList<OwnedTertiaryBeamlets.Head>();
        for (int pair = 0; pair < 4; pair++) {
            int dottedX = 100 + pair * 125;
            int hookedX = dottedX + 55;
            drawBeamedDyad(gray, dottedX);
            drawBeamedDyad(gray, hookedX);
            ink(gray, shaftX(dottedX), BEAM_TIP - 7, shaftX(hookedX) + 2, BEAM_TIP);
            ink(gray, shaftX(dottedX), BEAM_TIP - 21, shaftX(hookedX) + 2, BEAM_TIP - 14);
            int hookEnd = shaftX(hookedX) - (pair == 0 && !attachFirstHook ? 18 : 0);
            ink(gray, hookEnd - 14, BEAM_TIP - 35, hookEnd, BEAM_TIP - 26);
            ink(gray, dottedX + 15, UPPER_HEAD - 2, dottedX + 18, UPPER_HEAD + 1);
            ink(gray, dottedX + 15, LOWER_HEAD - 2, dottedX + 18, LOWER_HEAD + 1);
            addDyad(heads, dottedX, 1, 2, 0);
            addDyad(heads, hookedX, 0, pair < 2 ? 2 : 3, 0);
        }
        int closingX = 590;
        ink(gray, shaftX(closingX), UPPER_HEAD, shaftX(closingX) + 2, LOWER_HEAD + 39);
        oval(gray, closingX, UPPER_HEAD, true);
        oval(gray, closingX, LOWER_HEAD, true);
        addDyad(heads, closingX, 0, 0, 2);
        var measure = new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH,
                34f / HEIGHT, 182f / HEIGHT);
        return OwnedTertiaryBeamlets.recover(gray, WIDTH, HEIGHT, heads, List.of(measure));
    }

    private static void addDyad(
            List<OwnedTertiaryBeamlets.Head> heads, int x, int dots, int beams, int duration) {
        for (int y : new int[] {UPPER_HEAD, LOWER_HEAD}) {
            int step = y == UPPER_HEAD ? 4 : 0;
            var note = new ScoreNoteEvent(0, (x - LEFT) / (float) (RIGHT - LEFT), step, 0, 1,
                    y / (float) HEIGHT, false, dots, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                    duration).withStemDirection(-1);
            heads.add(new OwnedTertiaryBeamlets.Head(heads.size(), note, x, y, ESTIMATED_GAP));
        }
    }

    private static int shaftX(int headX) {
        return headX - 10;
    }

    private static void drawBeamedDyad(byte[] gray, int x) {
        ink(gray, shaftX(x), UPPER_HEAD, shaftX(x) + 2, BEAM_TIP);
        oval(gray, x, UPPER_HEAD, false);
        oval(gray, x, LOWER_HEAD, false);
    }

    private static void oval(byte[] gray, int centerX, int centerY, boolean hollow) {
        for (int y = -5; y <= 5; y++) {
            for (int x = -10; x <= 10; x++) {
                if (x * x + 4 * y * y <= 100) {
                    boolean counter = hollow && x * x + 4 * y * y < 43;
                    gray[(centerY + y) * WIDTH + centerX + x] = counter ? (byte) 255 : 0;
                }
            }
        }
    }

    private static void ink(byte[] gray, int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
        }
    }
}
