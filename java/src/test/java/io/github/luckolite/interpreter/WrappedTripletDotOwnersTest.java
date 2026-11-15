// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class WrappedTripletDotOwnersTest {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 170;
    private static final int GAP = 16;
    private static final float HEAD_X = 50;
    private static final float HEAD_Y = 76;
    private static final String[] FINGERING_THREE = {
        "...#####....",
        "..########..",
        ".###...####.",
        ".##......##.",
        ".........###",
        ".........###",
        "........###.",
        "......####..",
        "...######...",
        "...#####....",
        "......####..",
        "........###.",
        ".........###",
        ".........###",
        ".........###",
        ".##.....###.",
        ".###...####.",
        "..########..",
        "...#####....",
    };

    @Test
    public void theNextAttackKeepsItsCompleteFingeringDigit() {
        assertTrue(fingeringOwnsDot(drawNumeral()));
    }

    @Test
    public void theNumeralCannotConsumeAnIndependentAugmentationCore() {
        byte[] raw = drawNumeral();
        for (int y = 73; y <= 77; y++) {
            Arrays.fill(raw, y * WIDTH + 65, y * WIDTH + 70, (byte) 0);
        }
        assertFalse(fingeringOwnsDot(raw));
    }

    @Test
    public void theLowerFingeringLobeNeedsTheRemainderOfTheDigit() {
        byte[] raw = drawNumeral();
        for (int y = 66; y <= 75; y++) {
            Arrays.fill(raw, y * WIDTH + 76, y * WIDTH + 88, (byte) 255);
        }
        assertFalse(fingeringOwnsDot(raw));
    }

    private static byte[] drawNumeral() {
        byte[] raw = new byte[WIDTH * HEIGHT];
        Arrays.fill(raw, (byte) 255);
        for (int y = 0; y < FINGERING_THREE.length; y++) {
            for (int x = 0; x < FINGERING_THREE[y].length(); x++) {
                if (FINGERING_THREE[y].charAt(x) == '#') raw[(66 + y) * WIDTH + 76 + x] = 0;
            }
        }
        return raw;
    }

    private static boolean fingeringOwnsDot(byte[] gray) {
        var next =
                new ScoreNoteEvent(0, 82f / WIDTH, 8, 0, 1, 100f / HEIGHT, false)
                        .withStemDirection(-1);
        return WrappedEighthTriplets.fingeringThreeOwnsDot(
                gray, WIDTH, HEIGHT, 0, WIDTH, HEAD_X, HEAD_Y, next, GAP);
    }
}
