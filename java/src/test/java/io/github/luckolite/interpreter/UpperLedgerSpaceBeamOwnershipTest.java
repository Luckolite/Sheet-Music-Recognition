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
    private static final int HEIGHT = 360;
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
    public void middleHeadKeepsBothRailsWithinRasterRounding() throws Exception {
        int[] rounded = {53, 98, 130};
        var ink = ink(rounded, MIDDLE, 1);
        assertTrue(admitted(ink, ink, rounded, MIDDLE, 1));

        int[] separated = {53, 99, 131};
        var wide = ink(separated, MIDDLE, 1);
        assertFalse(admitted(wide, wide, separated, MIDDLE, 1));
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

    @Test
    public void interstaffApexUsesFiveOwnedRulesAndTheUnbrokenSourceRail() throws Exception {
        int shift = 128;
        int[] heights = Arrays.stream(APEX).map(y -> y + shift).toArray();
        var unshifted = ink(APEX, 0);
        var gray = new byte[WIDTH * HEIGHT];
        var labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        System.arraycopy(unshifted, 0, gray, shift * WIDTH, (HEIGHT - shift) * WIDTH);
        for (int row = 16; row <= 80; row += 16)
            paint(gray, 2, row, WIDTH - 3, row, (byte) 0);
        for (int row = (int) TOP + shift; row <= BOTTOM + shift; row += 16) {
            paint(gray, 2, row, WIDTH - 3, row, (byte) 0);
            paint(labels, 2, row, WIDTH - 3, row, (byte) 4);
        }
        assertTrue(admitted(labels, gray, gray, XS, heights, 0, shift, 80));
        var noRawStaff = gray.clone();
        paint(noRawStaff, 2, (int) BOTTOM + shift, WIDTH - 3, (int) BOTTOM + shift, (byte) 255);
        assertFalse(admitted(labels, gray, noRawStaff, XS, heights, 0, shift, 80));
        var noRawLedger = gray.clone();
        paint(noRawLedger, 36, 27 + shift, 42, 33 + shift, (byte) 255);
        paint(noRawLedger, 64, 27 + shift, 70, 33 + shift, (byte) 255);
        assertFalse(admitted(labels, gray, noRawLedger, XS, heights, 0, shift, 80));
        assertFalse(admitted(labels, gray, gray, XS, heights, 0, shift, 142));
    }

    @Test
    public void closedDoubleRailOwnsFilledTerminalWithFourShortInnerRules() throws Exception {
        int[] centers = {53, 92};
        int[] heights = {62, 46};
        var page = ink(centers, heights, 1);
        paint(page, centers[0] - 10, 142, centers[1] - 8, 147, (byte) 0);
        paint(page, centers[0] - 10, 154, centers[1] - 8, 159, (byte) 0);
        paint(page, centers[1] - 14, 45, centers[1] - 11, 47, (byte) 255);
        paint(page, centers[1] + 11, 45, centers[1] + 14, 47, (byte) 255);
        assertTrue(admitted(page, page, centers, heights, 1, "closedDoubleRailUpperLedger"));

        var missingRail = page.clone();
        paint(missingRail, 61, 140, 72, 150, (byte) 255);
        assertFalse(admitted(page, missingRail, centers, heights, 1, "closedDoubleRailUpperLedger"));
        var missingLedger = page.clone();
        paint(missingLedger, 78, 77, 106, 79, (byte) 255);
        assertFalse(admitted(missingLedger, page, centers, heights, 1, "closedDoubleRailUpperLedger"));
    }

    private static boolean admitted(byte[] gray, byte[] raw, int[] centers,
            int[] heights, int ownerIndex, String gate) throws Exception {
        return admitted(new byte[gray.length], gray, raw, centers, heights, ownerIndex, 0, 0, gate);
    }

    @Test
    public void tallerFilledUpperSpaceUsesItsInteriorAndBothOpticalLinks() throws Exception {
        int[] heights = {54, 70, 86};
        byte[] image = ink(XS, heights, 0);
        paint(image, 54, 43, 57, 45, (byte) 0);
        paint(image, 48, 63, 51, 64, (byte) 0);
        assertTrue(admitted(new byte[image.length], image, image, XS, heights, 0, 0, 0,
                "upperLedgerHeadOnSingleRail", true));
        var hollow = image.clone();
        paint(hollow, 47, 50, 57, 56, (byte) 255);
        assertFalse(admitted(new byte[image.length], image, hollow, XS, heights, 0, 0, 0,
                "upperLedgerHeadOnSingleRail", true));
        var missingLink = image.clone();
        paint(missingLink, 58, 150, 69, 166, (byte) 255);
        assertFalse(admitted(new byte[image.length], missingLink, image, XS, heights, 0, 0, 0,
                "upperLedgerHeadOnSingleRail", true));
    }

    @Test
    public void openingOfADoubleBeamOwnsAHighFilledSpaceBelowTheBoundary() throws Exception {
        int[] heights = {62, 48, 62};
        var page = ink(XS, heights, 1);
        // No on-head ledger and no rail crossing the left group break.
        paint(page, 71, 45, 75, 51, (byte) 255);
        paint(page, 95, 45, 99, 51, (byte) 255);
        paint(page, 59, 151, 73, 162, (byte) 255);
        for (int rule = 62; rule <= 110; rule += 16)
            paint(page, 75, rule - 1, 95, rule + 1, (byte) 0);
        paint(page, 75, 142, 109, 147, (byte) 0);
        assertTrue(admitted(page, page, XS, heights, 1, "upperSpaceAtDoubleBeamEdge"));

        var missingRail = page.clone();
        paint(missingRail, 91, 140, 101, 150, (byte) 255);
        assertFalse(admitted(page, missingRail, XS, heights, 1, "upperSpaceAtDoubleBeamEdge"));
        var missingLedger = page.clone();
        paint(missingLedger, 73, 75, 98, 81, (byte) 255);
        assertFalse(admitted(missingLedger, page, XS, heights, 1, "upperSpaceAtDoubleBeamEdge"));
    }

    @Test
    public void terminalHighFilledSpaceRequiresTwoIncomingPairsAndADistinctNextGroup() throws Exception {
        int[] centers = {38, 70, 104, 143};
        int[] heights = {61, 69, 53, 64};
        var page = ink(centers, heights, 2);
        paint(page, 106, 151, 125, 164, (byte) 255);
        paint(page, 28, 142, 96, 147, (byte) 0);
        assertTrue(admitted(page, page, centers, heights, 2, "upperSpaceAtDoubleBeamEdge"));
        var noEarlierPair = page.clone();
        paint(noEarlierPair, 42, 141, 53, 149, (byte) 255);
        assertFalse(admitted(noEarlierPair, page, centers, heights, 2, "upperSpaceAtDoubleBeamEdge"));
        var joinedNext = page.clone();
        paint(joinedNext, 94, 142, 135, 147, (byte) 0);
        paint(joinedNext, 94, 155, 135, 159, (byte) 0);
        assertFalse(admitted(page, joinedNext, centers, heights, 2, "upperSpaceAtDoubleBeamEdge"));
    }

    private static byte[] ink(int[] heights, int owner) {
        return ink(XS, heights, owner);
    }

    private static byte[] ink(int[] centers, int[] heights, int owner) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int i = 0; i < centers.length; i++) {
            int x = centers[i], y = heights[i];
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
        paint(gray, centers[0] - 10, 155, centers[centers.length - 1] - 8, 159, (byte) 0);
        return gray;
    }

    private static void paint(byte[] gray, int left, int top, int right, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, value);
    }

    private static boolean admitted(byte[] gray, byte[] raw, int[] heights, int ownerIndex)
            throws Exception {
        return admitted(gray, raw, XS, heights, ownerIndex);
    }

    private static boolean admitted(byte[] gray, byte[] raw, int[] centers,
            int[] heights, int ownerIndex) throws Exception {
        return admitted(new byte[gray.length], gray, raw, centers, heights, ownerIndex, 0, 0);
    }

    private static boolean admitted(byte[] labels, byte[] gray, byte[] raw, int[] centers,
            int[] heights, int ownerIndex, int shift, float precedingBottom) throws Exception {
        return admitted(labels, gray, raw, centers, heights, ownerIndex, shift, precedingBottom,
                "upperLedgerHeadOnSingleRail");
    }

    private static boolean admitted(byte[] labels, byte[] gray, byte[] raw, int[] centers,
            int[] heights, int ownerIndex, int shift, float precedingBottom, String gate) throws Exception {
        return admitted(labels, gray, raw, centers, heights, ownerIndex, shift, precedingBottom, gate, false);
    }

    private static boolean admitted(byte[] labels, byte[] gray, byte[] raw, int[] centers,
            int[] heights, int ownerIndex, int shift, float precedingBottom, String gate,
            boolean tallerApex) throws Exception {
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
        var heads = new Object[centers.length];
        for (int i = 0; i < heads.length; i++)
            heads[i] =
                    componentShape.newInstance(
                            310,
                            centers[i] - 10,
                            centers[i] + 10,
                            heights[i] - (tallerApex && i == ownerIndex ? 11 : 9),
                            heights[i] + (tallerApex && i == ownerIndex ? 10 : 9),
                            (float) centers[i],
                            (float) heights[i]);
        var staffShape = staff.getDeclaredConstructor(float.class, float.class, float.class);
        staffShape.setAccessible(true);
        var owner = staffShape.newInstance(TOP + shift, BOTTOM + shift, GAP);
        var physical =
                shift == 0
                        ? owner
                        : staffShape.newInstance(precedingBottom - 4 * GAP, precedingBottom, GAP);
        var probe =
                outer.getDeclaredMethod(
                        gate,
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
                        labels,
                        gray,
                        raw,
                        WIDTH,
                        HEIGHT,
                        heads[ownerIndex],
                        physical,
                        owner,
                        List.of(owner),
                        List.of(heads));
    }
}
