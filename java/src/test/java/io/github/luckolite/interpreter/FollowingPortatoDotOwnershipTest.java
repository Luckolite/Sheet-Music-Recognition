// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class FollowingPortatoDotOwnershipTest {
    private static final int WIDTH = 220, HEIGHT = 175;
    private static final float GAP = 16;

    @Test
    public void aDottedTenutoAboveTheNextAttackOwnsItsDot() throws Exception {
        assertTrue(read(true, true, false));
    }

    @Test
    public void keepsAugmentationWithoutBothPrintedMarksAndTheSharedBeam() throws Exception {
        assertFalse(read(false, true, false));
        assertFalse(read(true, false, false));
        assertFalse(read(true, true, true));
    }

    private static boolean read(boolean dash, boolean secondBeam, boolean ownDot)
            throws Exception {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        head(gray, 70, 80);
        head(gray, 103, 88);
        fill(gray, 61, 81, 63, 146);
        fill(gray, 94, 89, 96, 146);
        fill(gray, 61, 140, 96, 146);
        if (secondBeam) fill(gray, 61, 129, 96, 135);
        fill(gray, 59, 52, 82, 57);
        if (dash) fill(gray, 92, 60, 115, 65);
        dot(gray, 70, 62);
        dot(gray, 103, 70);
        if (ownDot) dot(gray, 89, 78);
        Class<?> owner = OmrScoreInterpreter.class;
        var type = Class.forName(owner.getName() + "$Component");
        var make = type.getDeclaredConstructors()[0];
        make.setAccessible(true);
        var first = make.newInstance(170, 61, 79, 73, 87, 70f, 80f);
        var next = make.newInstance(170, 94, 112, 81, 95, 103f, 88f);
        int x = ownDot ? 89 : 103, y = ownDot ? 78 : 70;
        var mark = make.newInstance(21, x - 2, x + 2, y - 2, y + 2, (float) x, (float) y);
        var check = owner.getDeclaredMethod("followingPortatoOwnsDot",
                byte[].class, int.class, int.class, type, type, List.class, float.class);
        check.setAccessible(true);
        return (boolean) check.invoke(null, gray, WIDTH, HEIGHT, first, mark, List.of(first, next), GAP);
    }

    private static void head(byte[] gray, int x, int y) {
        for (int dy = -7; dy <= 7; dy++)
            for (int dx = -9; dx <= 9; dx++)
                if (dx * dx / 81.0 + dy * dy / 49.0 <= 1)
                    gray[(y + dy) * WIDTH + x + dx] = 0;
    }

    private static void dot(byte[] gray, int x, int y) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++)
                if (dx * dx + dy * dy <= 5)
                    gray[(y + dy) * WIDTH + x + dx] = 0;
    }

    private static void fill(byte[] gray, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right, (byte) 0);
    }
}
