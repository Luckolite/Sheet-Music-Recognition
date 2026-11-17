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

    @Test
    public void theFlatBowlCannotBeUsedAsAnUnownedAugmentationDot() {
        byte[] paper = flatInk();
        var core = new WrappedTripletDotInk.Center(82, 90);
        assertTrue(WrappedTripletDotInk.singleFlatOwns(paper, WIDTH, HEIGHT, core, 108, 82, GAP));
        assertFalse(WrappedTripletDotInk.singleFlatOwns(paper, WIDTH, HEIGHT, core, 99, 82, GAP));
        for (int y = 60; y < 77; y++) Arrays.fill(paper, y * WIDTH + 76, y * WIDTH + 78, (byte) 255);
        assertFalse(WrappedTripletDotInk.singleFlatOwns(paper, WIDTH, HEIGHT, core, 108, 82, GAP));
    }

    @Test
    public void theSemanticFlatSpineMustStillConnectToItsOwnBowlCore() {
        byte[] ink = flatInk();
        byte[] labels = new byte[ink.length];
        for (int i = 0; i < ink.length; i++)
            if (ink[i] == 0) labels[i] = OmrMeasurePostProcessor.CLEF_OR_KEY;
        var core = new WrappedTripletDotInk.Center(82, 90);
        assertTrue(WrappedTripletDotInk.labelledFlatOwns(labels, ink, WIDTH, HEIGHT, core, 105, 83, GAP));
        for (int y = 65; y <= 74; y++) Arrays.fill(ink, y * WIDTH + 76, y * WIDTH + 78, (byte) 255);
        assertFalse(WrappedTripletDotInk.labelledFlatOwns(labels, ink, WIDTH, HEIGHT, core, 105, 83, GAP));
    }

    private static byte[] flatInk() {
        byte[] paper = new byte[WIDTH * HEIGHT];
        Arrays.fill(paper, (byte) 255);
        for (int y = 60; y <= 96; y++) Arrays.fill(paper, y * WIDTH + 76, y * WIDTH + 78, (byte) 0);
        for (int y = 76; y <= 88; y++) Arrays.fill(paper, y * WIDTH + 85, y * WIDTH + 88, (byte) 0);
        for (int y = 76; y <= 78; y++) Arrays.fill(paper, y * WIDTH + 80, y * WIDTH + 86, (byte) 0);
        for (int y = 87; y <= 90; y++) Arrays.fill(paper, y * WIDTH + 76, y * WIDTH + 87, (byte) 0);
        return paper;
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
