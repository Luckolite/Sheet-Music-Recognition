// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class UpperLedgerSpaceCentroidTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 230;
    private static final int X = 70;
    private static final float GAP = 16;

    @Test
    public void ledgerClippedOvalStillBelongsToTheSpaceAboveTheTwoRules() {
        var gray = paper();
        rule(gray, X - 14, X + 14, 91);
        rule(gray, X - 14, X + 14, 106);
        assertTrue(preserves(gray, 82, 84));
        assertFalse(preserves(gray, 90, 91));
    }

    @Test
    public void isolatedBarAndContinuingBeamDoNotSupplyTwoLedgerRules() {
        var one = paper();
        rule(one, X - 14, X + 14, 91);
        assertFalse(preserves(one, 82, 84));

        var beam = one.clone();
        rule(beam, X - 34, X + 35, 106);
        assertFalse(preserves(beam, 82, 84));
    }

    @Test
    public void competingStripeThroughTheHeadVetoesTheSpace() {
        var gray = paper();
        rule(gray, X - 14, X + 14, 91);
        rule(gray, X - 14, X + 14, 106);
        rule(gray, X - 14, X + 14, 85);
        assertFalse(preserves(gray, 82, 84));
    }

    @Test
    public void attachedShaftAndThreeIndependentRulesProveTheHighFilledSpace() throws Exception {
        var ink = paper();
        int headY = 65;
        for (int y = headY - 9; y <= headY + 9; y++) {
            double vertical = (y - headY) / 9.4;
            int wing = (int) Math.floor(10.8 * Math.sqrt(1 - vertical * vertical));
            Arrays.fill(ink, y * WIDTH + X - wing, y * WIDTH + X + wing + 1, (byte) 0);
        }
        for (int y = headY; y < 154; y++)
            Arrays.fill(ink, y * WIDTH + X - 10, y * WIDTH + X - 7, (byte) 0);
        for (int y = 75; y <= 107; y += 16) rule(ink, X - 14, X + 14, y);
        assertTrue(sameSpace(ink, ink));

        var cut = ink.clone();
        for (int y = 89; y <= 93; y++)
            Arrays.fill(cut, y * WIDTH + X - 16, y * WIDTH + X + 17, (byte) 255);
        assertFalse(sameSpace(ink, cut));

        var outer = ink.clone();
        rule(outer, X - 14, X + 14, 62);
        assertFalse(sameSpace(outer, ink));
    }

    private static boolean sameSpace(byte[] gray, byte[] raw) throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var component = Class.forName(interpreter.getName() + "$Component");
        var constructor = component.getDeclaredConstructor(int.class, int.class, int.class,
                int.class, int.class, float.class, float.class);
        constructor.setAccessible(true);
        var head = constructor.newInstance(317, X - 10, X + 10, 56, 75, (float) X, 65f);
        var proof = interpreter.getDeclaredMethod("pairedInnerRulesBelowFilledSpace",
                byte[].class, byte[].class, int.class, int.class, component, float.class, float.class,
                int.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, gray, raw, WIDTH, HEIGHT, head, 190f, GAP, 16);
    }

    private static boolean preserves(byte[] gray, float center, float opened) {
        return OmrScoreInterpreter.preservesUpperLedgerSpace(
                gray, WIDTH, HEIGHT, X, center, opened, 186, GAP, 12);
    }

    private static byte[] paper() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        return gray;
    }

    private static void rule(byte[] gray, int left, int right, int y) {
        for (int row = y - 1; row <= y + 1; row++) {
            Arrays.fill(gray, row * WIDTH + left, row * WIDTH + right + 1, (byte) 0);
        }
    }
}
