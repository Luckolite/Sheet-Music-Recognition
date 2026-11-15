// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class DownstemRailOwnershipTest {
    private static final int WIDTH = 400, HEIGHT = 300, GAP = 16;
    private final byte[] labels = new byte[WIDTH * HEIGHT];
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    public DownstemRailOwnershipTest() {
        Arrays.fill(gray, (byte) 255);
        for (int y = 125; y <= 189; y += GAP) ink(15, 355, y, y, (byte) 4);
        for (int x = 60; x <= 120; x++) ink(x, x, 170, 198, (byte) 5);
        for (int x = 120; x <= 190; x++) {
            int y = 198 + Math.round((x - 120) * 3f / 70);
            ink(x, x, y - 9, y, (byte) 5);
            ink(x, x, y - 22, y - 15, (byte) 5);
        }
        for (int stem : new int[] {60, 120, 190}) {
            int y = stem == 190 ? 201 : 198;
            ink(stem, stem + 2, 103, y, (byte) 1);
            head(stem + 10, 103);
            head(stem + 10, 144);
        }
        ink(154, 155, 118, 160, (byte) 3);
        ink(162, 164, 135, 169, (byte) 3);
        ink(154, 162, 136, 139, (byte) 3);
        ink(154, 162, 151, 154, (byte) 3);
    }

    @Test
    public void accidentalSpineCannotDivideTwoConnectedDownstems() {
        assertEquals(1, joins());
    }

    @Test
    public void aFullPrintedDividerKeepsBothBars() {
        ink(154, 156, 125, 189, (byte) 0);
        assertEquals(2, joins());
    }

    @Test
    public void anAdjacentPairClaimsATinyStemlessIslandOnItsMergedStrip() throws Exception {
        assertTrue(ownsIsland(true));
    }

    @Test
    public void theMergedStripAloneCannotClaimAHead() throws Exception {
        assertFalse(ownsIsland(false));
    }

    private int joins() {
        float top = 93f / HEIGHT, bottom = 223f / HEIGHT;
        var left = new MeasureRegion(20f / WIDTH, 146f / WIDTH, top, bottom);
        var right = new MeasureRegion(164f / WIDTH, 340f / WIDTH, top, bottom);
        return BeamedFlatBarline.join(List.of(left, right), labels, gray, WIDTH, HEIGHT).size();
    }

    private boolean ownsIsland(boolean continuingPair) throws Exception {
        var omr = OmrScoreInterpreter.class;
        var component = Class.forName(omr.getName() + "$Component");
        var shape =
                component.getDeclaredConstructor(
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        float.class,
                        float.class);
        shape.setAccessible(true);
        var staff = Class.forName(omr.getName() + "$Staff");
        var staffShape = staff.getDeclaredConstructor(float.class, float.class, float.class);
        staffShape.setAccessible(true);
        var stripe = shape.newInstance(30, 85, 91, 181, 185, 88f, 183f);
        var next = shape.newInstance(310, 189, 211, 135, 153, 200f, 144f);
        var method =
                omr.getDeclaredMethod(
                        "mergedStripBeforeClosedBeam",
                        byte[].class,
                        int.class,
                        int.class,
                        int[].class,
                        int[].class,
                        component,
                        staff,
                        List.class);
        method.setAccessible(true);
        return (boolean)
                method.invoke(
                        null,
                        gray,
                        WIDTH,
                        HEIGHT,
                        new int[] {60, 198, 1},
                        new int[] {120, 198, 1},
                        stripe,
                        staffShape.newInstance(125f, 189f, (float) GAP),
                        continuingPair ? List.of(next) : List.of());
    }

    private void head(int x, int y) {
        for (int dy = -8; dy <= 8; dy++) {
            int radius = Math.round(11f * (float) Math.sqrt(1 - dy * dy / 81f));
            ink(x - radius, x + radius, y + dy, y + dy, (byte) 2);
        }
    }

    private void ink(int left, int right, int top, int bottom, byte label) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                gray[y * WIDTH + x] = 0;
                if (label != 0) labels[y * WIDTH + x] = label;
            }
        }
    }
}
