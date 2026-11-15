// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class StaffIntersectingHookTest {
    private static final int WIDTH = 282, HEIGHT = 273, GAP = 16;
    private static final int STAFF_TOP = 126, OWN = 177, PEER = 126;

    @Test
    public void recognizesTheShortStrokeUnderAnIndependentlyVisibleStaffLine() {
        byte[] gray = example();
        assertTrue(ownsHook(gray));

        byte[] withoutHook = gray.clone();
        fill(withoutHook, OWN - 16, 145, OWN - 2, 153, 255);
        assertFalse(ownsHook(withoutHook));

        byte[] withoutPeerShaft = gray.clone();
        fill(withoutPeerShaft, PEER - 2, 126, PEER + 4, 141, 255);
        assertFalse(ownsHook(withoutPeerShaft));
    }

    @Test
    public void staffCrossingRibbonRequiresBothHooksAndABreakAfterTheFourthColumn() {
        var page = example();
        for (int x = 40; x < PEER - 1; x++) {
            int tip =
                    x < 85
                            ? Math.round(189 - (x - 39) * 4f / 46)
                            : Math.round(185 - (x - 85) * 3f / 40);
            fill(page, x, tip - 22, x + 1, tip - 17, 0);
            fill(page, x, tip - 7, x + 1, tip - 2, 0);
        }
        for (int stem : new int[] {40, 86}) {
            int tip = 178 + Math.round((OWN - stem) * .09f);
            fill(page, stem - 1, 71, stem + 1, tip, 0);
            head(page, stem + 10, 68);
            head(page, stem + 10, 109);
        }
        fill(page, 74, 150, 86, 157, 0);
        var attacks =
                java.util.List.of(
                        new ClosedOctaveBeamSource.Attack(0, 1, 50, 68, 50, 109),
                        new ClosedOctaveBeamSource.Attack(2, 3, 96, 68, 96, 109),
                        new ClosedOctaveBeamSource.Attack(4, 5, PEER + 10, 68, PEER + 10, 109),
                        new ClosedOctaveBeamSource.Attack(6, 7, OWN + 10, 59, OWN + 10, 100));
        assertTrue("complete four-column ribbon", ribbon(page, attacks));

        var missing = page.clone();
        fill(missing, OWN - 16, 145, OWN - 2, 153, 255);
        assertFalse(ribbon(missing, attacks));

        var missingFirst = page.clone();
        fill(missingFirst, 74, 150, 84, 157, 255);
        assertFalse(ribbon(missingFirst, attacks));

        var open = page.clone();
        for (int x = OWN; x < OWN + 30; x++) {
            int y = Math.round(174 - .09f * (x - OWN));
            fill(open, x, y - 3, x + 1, y + 3, 0);
        }
        assertFalse(ribbon(open, attacks));
    }

    private static boolean ribbon(
            byte[] paper, java.util.List<ClosedOctaveBeamSource.Attack> attacks) {
        return OwnedTertiaryBeamlets.provesOpeningStaffCrossingRibbon(
                paper, WIDTH, HEIGHT, GAP, STAFF_TOP, GAP, attacks, 237);
    }

    private static boolean ownsHook(byte[] gray) {
        return OwnedTertiaryBeamlets.provesBackwardHookUnderStaffLine(
                gray, WIDTH, HEIGHT, OWN + 10, 100, OWN + 10, 59, PEER + 10, 109, PEER + 10, 68,
                GAP, STAFF_TOP, GAP);
    }

    private static byte[] example() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int line = 0; line < 5; line++) {
            int y = STAFF_TOP + line * GAP;
            fill(gray, 63, y - 1, 225, y + 2, 0);
        }
        for (int x = PEER - 1; x <= OWN + 1; x++) {
            int slope = Math.round((OWN - x) * .09f);
            // Engraving is lightly fused where the two printed rails approach a staff rule.
            fill(gray, x, 159 + slope, x + 1, 166 + slope, 0);
            fill(gray, x, 166 + slope, x + 1, 171 + slope, 181);
            fill(gray, x, 171 + slope, x + 1, 178 + slope, 0);
        }
        fill(gray, OWN - 1, 61, OWN + 2, 178, 0);
        fill(gray, PEER - 1, 70, PEER + 2, 174, 0);
        fill(gray, PEER - 1, 174, PEER + 2, 176, 204);
        fill(gray, PEER - 1, 176, PEER + 2, 183, 0);
        fill(gray, OWN - 12, 146, OWN, 152, 0);
        head(gray, OWN + 10, 59);
        head(gray, OWN + 10, 100);
        head(gray, PEER + 10, 68);
        head(gray, PEER + 10, 109);
        return gray;
    }

    private static void head(byte[] gray, int x, int y) {
        fill(gray, x - 6, y - 3, x + 6, y + 4, 0);
        fill(gray, x - 9, y - 1, x - 5, y + 3, 0);
    }

    private static void fill(byte[] gray, int left, int top, int right, int bottom, int tone) {
        for (int y = top; y < bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right, (byte) tone);
    }
}
