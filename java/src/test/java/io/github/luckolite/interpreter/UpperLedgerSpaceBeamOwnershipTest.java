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
    private static final int[] YS = {86, 62, 46};

    @Test
    public void shortLedgerSpaceUsesItsOwnShaftAndTwoLinkedHeads() throws Exception {
        var ink = ink();
        assertTrue(admitted(ink, ink));
    }

    @Test
    public void sourceRailAndTwoInnerLedgerLinesAreRequired() throws Exception {
        var ink = ink();
        var noBridge = ink.clone();
        paint(noBridge, 56, 151, 67, 166, (byte) 255);
        assertFalse(admitted(ink, noBridge));
        var noInnerRule = ink.clone();
        paint(noInnerRule, 48, 107, 66, 112, (byte) 255);
        assertFalse(admitted(ink, noInnerRule));
    }

    private static byte[] ink() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int i = 0; i < XS.length; i++) {
            int x = XS[i], y = YS[i];
            for (int row = y - 9; row <= y + 9; row++) {
                double normalized = (row - y) / 9.4;
                int reach = (int) Math.floor(10.8 * Math.sqrt(1 - normalized * normalized));
                paint(gray, x - reach, row, x + reach, row, (byte) 0);
            }
            paint(gray, x - 10, y, x - 8, 159, (byte) 0);
            // The space head touches its upper short ledger and has a second line below.
            int start = i == 0 ? 94 : y;
            for (int rule = start; rule < TOP; rule += 16) {
                int half = i == 0 ? 11 : 14;
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

    private static boolean admitted(byte[] gray, byte[] raw) throws Exception {
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
        var lead =
                componentShape.newInstance(
                        310,
                        XS[0] - 10,
                        XS[0] + 10,
                        YS[0] - 9,
                        YS[0] + 9,
                        (float) XS[0],
                        (float) YS[0]);
        var second =
                componentShape.newInstance(
                        310,
                        XS[1] - 10,
                        XS[1] + 10,
                        YS[1] - 9,
                        YS[1] + 9,
                        (float) XS[1],
                        (float) YS[1]);
        var third =
                componentShape.newInstance(
                        310,
                        XS[2] - 10,
                        XS[2] + 10,
                        YS[2] - 9,
                        YS[2] + 9,
                        (float) XS[2],
                        (float) YS[2]);
        var staffShape = staff.getDeclaredConstructor(float.class, float.class, float.class);
        staffShape.setAccessible(true);
        var owner = staffShape.newInstance(TOP, BOTTOM, GAP);
        var probe =
                outer.getDeclaredMethod(
                        "upperLedgerSpaceOnSingleRail",
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
                        lead,
                        owner,
                        owner,
                        List.of(owner),
                        List.of(lead, second, third));
    }
}
