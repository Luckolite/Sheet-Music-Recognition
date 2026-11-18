// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class WideLedgerSixthBarlineTest {
    private static final int WIDTH = 430;
    private static final int HEIGHT = 380;
    private static final float GAP = 16;

    @Test
    public void wideBeamOpeningRequiresPaperDividerAndTwoRightRails() throws Exception {
        assertTrue(opening(true, true, false));
        assertFalse(opening(false, true, false));
        assertFalse(opening(true, false, false));
        assertFalse(opening(true, true, true));
    }

    private static boolean opening(boolean divider, boolean secondRail, boolean joinedLeft)
            throws Exception {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 230; y <= 294; y += (int) GAP) fill(gray, 30, 400, y, y + 1);
        if (divider) fill(gray, 185, 187, 230, 295);

        int left = 129, middle = 216, right = 283;
        fill(gray, left, left + 3, 141, 266);
        fill(gray, middle, middle + 3, 133, 266);
        fill(gray, right, right + 3, 141, 266);
        fill(gray, middle, right + 3, 245, 252);
        if (secondRail) fill(gray, middle, right + 3, 258, 266);
        if (joinedLeft) {
            fill(gray, left, middle + 3, 245, 252);
            fill(gray, left, middle + 3, 258, 266);
        }

        fill(gray, left + 2, left + 22, 138, 147);
        fill(gray, middle + 2, middle + 22, 130, 139);
        fill(gray, right + 2, right + 22, 138, 147);

        Class<?> component =
                Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var head = component.getDeclaredConstructor(int.class, int.class, int.class,
                int.class, int.class, float.class, float.class);
        head.setAccessible(true);
        Object before = head.newInstance(321, left, left + 22, 134, 150, left + 11f, 142f);
        Object center = head.newInstance(321, middle, middle + 22, 126, 142, middle + 11f, 134f);
        Object after = head.newInstance(321, right, right + 22, 134, 150, right + 11f, 142f);
        Class<?> staffType = Class.forName(OmrScoreInterpreter.class.getName() + "$Staff");
        var makeStaff = staffType.getDeclaredConstructor(float.class, float.class, float.class);
        makeStaff.setAccessible(true);
        Object staff = makeStaff.newInstance(230f, 294f, GAP);
        var proof = OmrScoreInterpreter.class.getDeclaredMethod("rightDoubleRailsAfterBarline",
                byte[].class, int.class, int.class, component, component, component, staffType);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, gray, WIDTH, HEIGHT, before, center, after, staff);
    }

    private static void fill(byte[] gray, int left, int right, int top, int bottom) {
        for (int y = top; y < bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right, (byte) 0);
    }
}
