// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class LedgerStaffAliasTest {
    private static final int W = 720;
    private static final int H = 284;
    private static final int GAP = 12;
    private static final int TOP = 56;
    private static final int LOWER = TOP + 5 * GAP;

    @Test
    public void rejectsAlternatingStacksAboveProvedStaff() {
        byte[] gray = paper();
        staff(gray, LOWER, 26, W - 26);
        for (int line = 0; line < 5; line++)
            for (int x = 34 + line * 12; x < W - 48; x += 70)
                rail(gray, TOP + line * GAP, x, x + 38);
        assertTrue(alias(gray, TOP));
    }

    @Test
    public void keepsCloseGrandStaff() {
        byte[] gray = paper();
        staff(gray, TOP, 26, W - 26);
        staff(gray, LOWER, 26, W - 26);
        assertFalse(alias(gray, TOP));
    }

    @Test
    public void keepsASecondStaffWithOnlyAShortPrintedPartVisible() {
        byte[] gray = paper();
        staff(gray, TOP, 510, 573);
        staff(gray, LOWER, 26, W - 26);
        assertFalse(alias(gray, TOP));
    }

    @Test
    public void requiresProvedLowerStaffAndSharedLedgerPhase() {
        byte[] gray = paper();
        assertFalse(alias(gray, TOP));
        staff(gray, LOWER, 26, W - 26);
        assertFalse(alias(gray, TOP - 5));
    }

    @Test
    public void threeSharedRailsAndHairpinArmsDoNotMakeAStaff() {
        byte[] gray = paper();
        staff(gray, LOWER, 26, W - 26);
        int candidates = LOWER - 2 * GAP;
        rail(gray, candidates, 120, 245);
        rail(gray, candidates + GAP, 126, 239);
        assertTrue(LedgerStaffAlias.weakerPhase(gray, W, H,
                GAP, (line, x) -> candidates + line * GAP,
                GAP, (line, x) -> LOWER + line * GAP, -2, -1, -1));
    }

    @Test
    public void distinguishesLedgerExtensionFromASeparatelyFramedShortPart() {
        byte[] gray = paper();
        staff(gray, TOP, 26, W - 26);
        staff(gray, LOWER, 245, 345);
        assertTrue(LedgerStaffAlias.weakerPhase(gray, W, H,
                GAP, (line, x) -> LOWER + line * GAP,
                GAP, (line, x) -> TOP + line * GAP, 5, 120, 520));
        assertFalse(LedgerStaffAlias.weakerPhase(gray, W, H,
                GAP, (line, x) -> LOWER + line * GAP,
                GAP, (line, x) -> TOP + line * GAP, 5, 240, 350));
    }

    @Test
    public void preservesTheFiveRuleBodyOverAOneLineBeamAlias() {
        byte[] gray = paper();
        staff(gray, TOP, 26, W - 26);
        rail(gray, TOP - GAP, 204, 285);
        assertTrue(LedgerStaffAlias.retainsBodyPhase(
                gray, W, H, TOP + 4 * GAP, GAP, TOP + 3 * GAP, GAP, 0));
        rail(gray, TOP - GAP, 26, W - 26);
        assertFalse(LedgerStaffAlias.retainsBodyPhase(
                gray, W, H, TOP + 4 * GAP, GAP, TOP + 3 * GAP, GAP, 0));
    }

    private static boolean alias(byte[] gray, int upper) {
        return LedgerStaffAlias.unsupported(gray, W, H,
                GAP, (line, x) -> upper + line * GAP,
                GAP, (line, x) -> LOWER + line * GAP);
    }

    private static byte[] paper() {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        return gray;
    }

    private static void staff(byte[] gray, int top, int from, int to) {
        for (int line = 0; line < 5; line++)
            rail(gray, top + line * GAP, from, to);
    }

    private static void rail(byte[] gray, int y, int from, int to) {
        Arrays.fill(gray, y * W + from, y * W + to, (byte) 0);
    }
}
