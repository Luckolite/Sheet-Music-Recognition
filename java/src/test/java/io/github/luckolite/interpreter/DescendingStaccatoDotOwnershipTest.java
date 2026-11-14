// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Synthetic double-beamed notes with independent articulation and augmentation marks. */
public class DescendingStaccatoDotOwnershipTest {
    private static final int WIDTH = 170;
    private static final int HEIGHT = 170;
    private static final float STAFF_GAP = 16.5f;

    @Test
    public void aFollowingLowerHeadOwnsItsCenteredUpperDot() throws Exception {
        assertEquals(1, count(true, true, true));
    }

    @Test
    public void removingArticulationOrBreakingBeamPreservesBothDotReads() throws Exception {
        assertEquals(2, count(false, true, true));
        assertEquals(2, count(true, false, true));
    }

    @Test
    public void aDoubleDotWithoutTheFollowingOwnerStaysDouble() throws Exception {
        assertEquals(2, count(true, true, false));
    }

    private static int count(boolean haveArticulation, boolean haveContinuousBeams, boolean haveNext)
            throws Exception {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        oval(gray, 59, 58, 11, 8);
        if (haveNext) {
            oval(gray, 99, 66, 11, 8);
            for (int x = 47; x <= 90; x++) {
                int tip = 116 + Math.round((x - 47) * .125f);
                if (!haveContinuousBeams && x >= 67 && x <= 72) continue;
                rectangle(gray, x, tip - 8, x, tip);
                rectangle(gray, x, tip - 20, x, tip - 12);
            }
            rectangle(gray, 87, 66, 90, 121);
        }
        rectangle(gray, 47, 58, 50, 116);
        oval(gray, 77, 51, 3, 3);
        oval(gray, 99, 49, 2, 3);
        if (haveArticulation) oval(gray, 59, 33, 3, 3);

        var component = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var ctor = component.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        var current = ctor.newInstance(280, 48, 70, 50, 66, 59f, 58f);
        var next = ctor.newInstance(280, 88, 110, 58, 74, 99f, 66f);
        var method = OmrScoreInterpreter.class.getDeclaredMethod("countAugmentationDots",
                List.class, component, float.class, byte[].class, int.class, int.class,
                boolean.class, List.class, List.class);
        method.setAccessible(true);
        return (int) method.invoke(null, List.of(), current, STAFF_GAP, gray, WIDTH, HEIGHT,
                false, List.of(), haveNext ? List.of(current, next) : List.of(current));
    }

    private static void rectangle(byte[] gray, int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }

    private static void oval(byte[] gray, int cx, int cy, int rx, int ry) {
        for (int y = cy - ry; y <= cy + ry; y++)
            for (int x = cx - rx; x <= cx + rx; x++)
                if (Math.pow((x - cx) / (double) rx, 2) + Math.pow((y - cy) / (double) ry, 2) <= 1)
                    gray[y * WIDTH + x] = 0;
    }
}
