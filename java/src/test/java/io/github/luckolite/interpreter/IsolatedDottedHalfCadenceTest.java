// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class IsolatedDottedHalfCadenceTest {
    private static final int WIDTH = 400;
    private static final int HEIGHT = 260;
    private static final float GAP = 16.75f;
    private static final MeasureRegion PREVIOUS =
            new MeasureRegion(20f / WIDTH, 215f / WIDTH, 21f / HEIGHT, 158f / HEIGHT);
    private static final MeasureRegion CURRENT =
            new MeasureRegion(233f / WIDTH, 302f / WIDTH, 21f / HEIGHT, 158f / HEIGHT);
    private static final MeasureRegion NEXT =
            new MeasureRegion(20f / WIDTH, 355f / WIDTH, 180f / HEIGHT, 230f / HEIGHT);
    private static final PlayingTechniqueDetector.Staff STAFF =
            new PlayingTechniqueDetector.Staff(56, 123, GAP, 0, 1);

    @Test
    public void headCenteredTerminalFrameRecoversTheDottedHalf() throws Exception {
        var gray = source();
        float midpoint = (CURRENT.left() + CURRENT.right()) * WIDTH / 2;
        assertNull(PrintedDirectionStaff.printedRules(gray, WIDTH, HEIGHT, midpoint, 123, GAP));
        assertNull(
                TerminalDirectionRails.read(
                        gray, WIDTH, HEIGHT, midpoint, 123, GAP, PREVIOUS.left(), CURRENT.right()));
        assertTrue(proved(gray));
    }

    @Test
    public void extraHeadMissingDotOrSingleClosingRuleAbstain() throws Exception {
        var gray = source();
        var extra = gray.clone();
        paint(extra, 271, 286, 112, 119, 0);
        assertFalse(proved(extra));
        var undotted = gray.clone();
        paint(undotted, 272, 282, 92, 103, 255);
        assertFalse(proved(undotted));
        var single = gray.clone();
        paint(single, 302, 305, 55, 123, 255);
        assertFalse(proved(single));
    }

    @Test
    public void closeOpeningInRegularStaffFrameRequiresADotAndDoubleClose() throws Exception {
        var gray = source();
        paint(gray, 293, 305, 54, 124, 255);
        for (int y : new int[] {55, 72, 89, 106, 122}) {
            paint(gray, 291, 321, y, y + 1, 0);
        }
        paint(gray, 306, 308, 55, 123, 0);
        paint(gray, 314, 316, 55, 123, 0);
        float midpoint = (CURRENT.left() + CURRENT.right()) * WIDTH / 2;
        assertNotNull(PrintedDirectionStaff.printedRules(gray, WIDTH, HEIGHT, midpoint, 123, GAP));
        assertTrue(proved(gray));

        var undotted = gray.clone();
        paint(undotted, 272, 282, 92, 103, 255);
        assertFalse(proved(undotted));

        var singleRule = gray.clone();
        paint(singleRule, 314, 316, 54, 124, 255);
        for (int y : new int[] {55, 72, 89, 106, 122}) {
            paint(singleRule, 314, 316, y, y + 1, 0);
        }
        assertFalse(proved(singleRule));
    }

    private static boolean proved(byte[] gray) throws Exception {
        var note =
                new ScoreNoteEvent(
                        1,
                        19f / 69,
                        2,
                        0,
                        1,
                        106f / HEIGHT,
                        false,
                        1,
                        0,
                        2,
                        2,
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
                        1);
        var proof =
                IsolatedDottedHalfCadence.class.getDeclaredMethod(
                        "proved",
                        byte[].class,
                        int.class,
                        int.class,
                        MeasureRegion.class,
                        MeasureRegion.class,
                        MeasureRegion.class,
                        ScoreNoteEvent.class,
                        List.class);
        proof.setAccessible(true);
        return (boolean)
                proof.invoke(
                        null, gray, WIDTH, HEIGHT, PREVIOUS, CURRENT, NEXT, note, List.of(STAFF));
    }

    private static byte[] source() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y : new int[] {55, 72, 89, 106, 122}) {
            paint(gray, 16, 303, y, y + 1, 0);
        }
        paint(gray, 223, 225, 55, 123, 0);
        paint(gray, 294, 296, 55, 123, 0);
        paint(gray, 302, 304, 55, 123, 0);
        for (int x = 240; x < 264; x++) {
            for (int y = 94; y < 119; y++) {
                double dx = x - 252;
                double dy = y - 106 + .25 * dx;
                if (dx * dx / 120 + dy * dy / 42 < 1 && dx * dx / 52 + dy * dy / 16 > 1) {
                    gray[y * WIDTH + x] = 0;
                }
            }
        }
        paint(gray, 261, 263, 55, 108, 0);
        for (int y = -4; y <= 4; y++) {
            for (int x = -4; x <= 4; x++) {
                if (x * x + y * y <= 16) gray[(98 + y) * WIDTH + 277 + x] = 0;
            }
        }
        for (int x : new int[] {187, 193, 203, 213}) {
            for (int y = 53; y < 118; y += 12) paint(gray, x - 2, x + 3, y, y + 5, 0);
        }
        return gray;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
        }
    }
}
