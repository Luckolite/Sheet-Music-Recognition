// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class UpperLedgerRoundingBoundaryTest {
    private static final int W = 160, H = 230, X = 73, Y = 82;
    private static final float GAP = 16;

    @Test
    public void twoPrintedShortRulesProveTheSameSpaceOnBothSidesOfTheEstimate() throws Exception {
        var image = noteAboveLedger();
        assertEquals(13, step(image, 181.75f));
        assertEquals(13, step(image, 182.25f));
    }

    @Test
    public void aSingleRuleOrLiveRuleThroughTheHeadCannotClaimTheSpace() throws Exception {
        var erasure = noteAboveLedger();
        paint(erasure, X - 15, X + 15, 103, 109, 255);
        assertFalse(proved(erasure));
        var through = noteAboveLedger();
        paint(through, X - 16, X + 16, 81, 83, 0);
        assertFalse(proved(through));
        var extended = noteAboveLedger();
        paint(extended, X - 33, X + 33, 105, 107, 0);
        assertFalse(proved(extended));
    }

    @Test
    public void nearbyInkRemainsIndependentOnlyAcrossPaperAtTheEndOfTheRule() throws Exception {
        var adjacent = noteAboveLedger();
        paint(adjacent, X + 18, X + 21, 84, 114, 0);
        assertEquals(13, step(adjacent, 181.75f));

        var joined = adjacent.clone();
        paint(joined, X + 13, X + 21, 89, 91, 0);
        assertFalse(proved(joined));
    }

    @Test
    public void sharpJoinedAtOneEndStillRequiresTheOtherLedgerAndTheOriginalSharp() throws Exception {
        var image = noteAboveLedger();
        paint(image, 48, 50, 61, 96, 0);
        paint(image, 55, 57, 62, 97, 0);
        for (int x = 45; x <= 60; x++) {
            int rise = Math.round((x - 52) * .24f);
            paint(image, x, x, 71 - rise, 74 - rise, 0);
            paint(image, x, x, 82 - rise, 85 - rise, 0);
        }
        paint(image, 55, X+14, 89, 91, 0);
        assertTrue(joinedSpace(image, image));
        var noOtherLedger = image.clone();
        paint(noOtherLedger, X-15, X+15, 103, 109, 255);
        assertFalse(joinedSpace(image, noOtherLedger));
        var noSharp = image.clone();
        paint(noSharp, 44, 58, 57, 88, 255);
        assertFalse(joinedSpace(image, noSharp));
        var continued = image.clone();
        paint(continued, X+10, X+29, 89, 91, 0);
        assertFalse(joinedSpace(image, continued));
    }

    private static boolean joinedSpace(byte[] normalized, byte[] source) throws Exception {
        var component = headClass();
        var read = OmrScoreInterpreter.class.getDeclaredMethod("sharpJoinedUpperLedgerSpace", byte[].class,
                byte[].class, int.class, int.class, component, float.class, float.class, int.class);
        read.setAccessible(true);
        return (boolean) read.invoke(null, normalized, source, W, H, head(component), 206.75f, GAP, 16);
    }

    private static int step(byte[] image, float bottom) throws Exception {
        var component = headClass();
        var read =
                OmrScoreInterpreter.class.getDeclaredMethod(
                        "printedPitchStep",
                        byte[].class,
                        int.class,
                        int.class,
                        component,
                        float.class,
                        float.class);
        read.setAccessible(true);
        return (int) read.invoke(null, image, W, H, head(component), bottom, GAP);
    }

    private static boolean proved(byte[] image) throws Exception {
        var component = headClass();
        var read =
                OmrScoreInterpreter.class.getDeclaredMethod(
                        "upperSpaceAtLedgerBoundary",
                        byte[].class,
                        int.class,
                        int.class,
                        component,
                        float.class,
                        float.class,
                        int.class);
        read.setAccessible(true);
        return (boolean) read.invoke(null, image, W, H, head(component), 181.75f, GAP, 12);
    }

    private static Class<?> headClass() throws Exception {
        return Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
    }

    private static Object head(Class<?> type) throws ReflectiveOperationException {
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(210, X - 11, X + 11, Y - 8, Y + 8, (float) X, (float) Y);
    }

    private static byte[] noteAboveLedger() {
        var image = new byte[W * H];
        Arrays.fill(image, (byte) 255);
        for (int dy = -8; dy <= 8; dy++) {
            for (int dx = -11; dx <= 11; dx++) {
                if (dx * dx / 121f + dy * dy / 64f <= 1) image[(Y + dy) * W + X + dx] = 0;
            }
        }
        paint(image, X - 14, X + 14, 89, 91, 0);
        paint(image, X - 14, X + 14, 105, 107, 0);
        return image;
    }

    private static void paint(byte[] image, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(image, y * W + left, y * W + right + 1, (byte) tone);
    }
}
