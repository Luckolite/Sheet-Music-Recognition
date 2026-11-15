// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class UpperLedgerSpaceBeamOwnershipTest {
    private static final int WIDTH = 220;
    private static final int HEIGHT = 220;
    private static final float GAP = 16;
    private static final float TOP = 126;
    private static final float BOTTOM = 190;
    private static final int[] XS = {53, 85, 117};
    private static final int[] LEADING = {86, 62, 46};
    private static final int[] MIDDLE = {86, 70, 46};
    private static final int[] APEX = {30, 46, 62};

    @Test
    public void shortLedgerSpaceUsesItsOwnShaftAndTwoLinkedHeads() throws Exception {
        var ink = ink(LEADING, 0);
        assertTrue(admitted(ink, ink, LEADING, 0));
    }

    @Test
    public void sourceRailAndTwoInnerLedgerLinesAreRequired() throws Exception {
        var ink = ink(LEADING, 0);
        var noBridge = ink.clone();
        paint(noBridge, 56, 151, 67, 166, (byte) 255);
        assertFalse(admitted(ink, noBridge, LEADING, 0));
        var noInnerRule = ink.clone();
        paint(noInnerRule, 48, 107, 66, 112, (byte) 255);
        assertFalse(admitted(ink, noInnerRule, LEADING, 0));
    }

    @Test
    public void middleHeadRequiresBothLinks() throws Exception {
        var ink = ink(MIDDLE, 1);
        assertTrue(admitted(ink, ink, MIDDLE, 1));
        var noLeft = ink.clone();
        paint(noLeft, 56, 151, 67, 166, (byte) 255);
        assertFalse(admitted(ink, noLeft, MIDDLE, 1));
        var noRight = ink.clone();
        paint(noRight, 91, 151, 102, 166, (byte) 255);
        assertFalse(admitted(ink, noRight, MIDDLE, 1));
    }

    @Test
    public void apexRequiresItsPrintedCrossingLedger() throws Exception {
        var ink = ink(APEX, 0);
        assertTrue(admitted(ink, ink, APEX, 0));
        var noWings = ink.clone();
        paint(noWings, 36, 27, 42, 33, (byte) 255);
        paint(noWings, 64, 27, 70, 33, (byte) 255);
        assertFalse(admitted(ink, noWings, APEX, 0));
    }

    private static byte[] ink(int[] heights, int owner) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int i = 0; i < XS.length; i++) {
            int x = XS[i], y = heights[i];
            for (int row = y - 9; row <= y + 9; row++) {
                double normalized = (row - y) / 9.4;
                int reach = (int) Math.floor(10.8 * Math.sqrt(1 - normalized * normalized));
                paint(gray, x - reach, row, x + reach, row, (byte) 0);
            }
            paint(gray, x - 10, y, x - 8, 159, (byte) 0);
            int step = Math.round((BOTTOM - y) / (GAP * .5f));
            boolean space = (step & 1) == 1;
            for (int rule = space ? y + 8 : y; rule < TOP; rule += 16) {
                int half = space && i == owner ? 11 : 14;
                paint(gray, x - half, rule - 1, x + half, rule + 1, (byte) 0);
            }
        }
        paint(gray, XS[0] - 10, 155, XS[2] - 8, 159, (byte) 0);
        return gray;
    }

    private static void paint(byte[] gray, int left, int top, int right, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, value);
    }

    private static boolean admitted(byte[] gray, byte[] raw, int[] heights, int ownerIndex)
            throws Exception {
        var outer = OmrScoreInterpreter.class;
        var component = Class.forName(outer.getName() + "$Component");
        var staff = Class.forName(outer.getName() + "$Staff");
        var componentShape =
                component.getDeclaredConstructor(
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        float.class,
                        float.class);
        componentShape.setAccessible(true);
        var heads = new Object[XS.length];
        for (int i = 0; i < heads.length; i++)
            heads[i] =
                    componentShape.newInstance(
                            310,
                            XS[i] - 10,
                            XS[i] + 10,
                            heights[i] - 9,
                            heights[i] + 9,
                            (float) XS[i],
                            (float) heights[i]);
        var staffShape = staff.getDeclaredConstructor(float.class, float.class, float.class);
        staffShape.setAccessible(true);
        var owner = staffShape.newInstance(TOP, BOTTOM, GAP);
        var probe =
                outer.getDeclaredMethod(
                        "upperLedgerHeadOnSingleRail",
                        byte[].class,
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        component,
                        staff,
                        staff,
                        List.class,
                        List.class);
        probe.setAccessible(true);
        return (boolean)
                probe.invoke(
                        null,
                        new byte[gray.length],
                        gray,
                        raw,
                        WIDTH,
                        HEIGHT,
                        heads[ownerIndex],
                        owner,
                        owner,
                        List.of(owner),
                        List.of(heads));
    }
}
