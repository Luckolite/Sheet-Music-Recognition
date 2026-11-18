// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class InsetDottedHalfCadenceTest {
    private static final int WIDTH = 400;
    private static final int HEIGHT = 260;
    private static final MeasureRegion PREVIOUS =
            new MeasureRegion(20f / WIDTH, 215f / WIDTH, 21f / HEIGHT, 156f / HEIGHT);
    private static final MeasureRegion CADENCE =
            new MeasureRegion(233f / WIDTH, 346f / WIDTH, 21f / HEIGHT, 156f / HEIGHT);
    private static final MeasureRegion NEXT =
            new MeasureRegion(22f / WIDTH, 350f / WIDTH, 180f / HEIGHT, 231f / HEIGHT);
    private static final PlayingTechniqueDetector.Staff STAFF =
            new PlayingTechniqueDetector.Staff(55, 119, 16, 0, 1);

    @Test
    public void fittedOuterEdgeCanContainADoubleRuleAndOneDottedHalf() throws Exception {
        var gray = source();
        assertTrue(proved(gray));

        var missingDot = gray.clone();
        paint(missingDot, 288, 301, 106, 116, 255);
        assertFalse(proved(missingDot));

        var singleRule = gray.clone();
        paint(singleRule, 342, 345, 55, 119, 255);
        assertFalse(proved(singleRule));

        var competingStem = gray.clone();
        paint(competingStem, 309, 311, 75, 111, 0);
        assertFalse(proved(competingStem));
    }

    @Test
    public void directlyLocatedDoubleStillAllowsItsProvedDot() throws Exception {
        var gray = source();
        var current = new MeasureRegion(233f / WIDTH, 340f / WIDTH, 21f / HEIGHT, 156f / HEIGHT);
        assertTrue(proved(gray, current, 35f / 107));
        paint(gray, 309, 311, 75, 111, 0);
        assertFalse(proved(gray, current, 35f / 107));
    }

    private static boolean proved(byte[] gray) throws Exception {
        return proved(gray, CADENCE, 35f / 113);
    }

    private static boolean proved(byte[] gray, MeasureRegion region, float position) throws Exception {
        var note = new ScoreNoteEvent(
                1, position, 1, 0, 1, 111f / HEIGHT, false,
                1, 0, 2, 2, 1, 0, 0, 30, false, 0, false, 0, 0, 1, 1);
        var proof = IsolatedDottedHalfCadence.class.getDeclaredMethod(
                "proved", byte[].class, int.class, int.class, MeasureRegion.class, MeasureRegion.class,
                MeasureRegion.class, ScoreNoteEvent.class, List.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(
                null, gray, WIDTH, HEIGHT, PREVIOUS, region, NEXT, note, List.of(STAFF));
    }

    private static byte[] source() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y : new int[] {55, 71, 87, 103, 119}) {
            paint(gray, 16, 345, y, y, 0);
        }
        paint(gray, 223, 225, 55, 119, 0);
        paint(gray, 334, 337, 55, 119, 0);
        paint(gray, 342, 345, 55, 119, 0);
        paint(gray, 276, 278, 60, 111, 0);
        for (int x = 256; x <= 280; x++) {
            for (int y = 102; y <= 120; y++) {
                double dx = x - 268;
                double dy = y - 111 + .25 * dx;
                if (dx * dx / 120 + dy * dy / 42 < 1 && dx * dx / 52 + dy * dy / 16 > 1) {
                    gray[y * WIDTH + x] = 0;
                }
            }
        }
        for (int y = -4; y <= 4; y++) {
            for (int x = -4; x <= 4; x++) {
                if (x * x + y * y <= 16) gray[(111 + y) * WIDTH + 294 + x] = 0;
            }
        }
        return gray;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
        }
    }
}
