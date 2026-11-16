// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class WideThirdHeaderRailsTest {
    private static final int WIDTH = 128;
    private static final int HEIGHT = 160;
    private static final float GAP = 16;

    @Test
    public void acceptsTwoCompleteRailsWithBriefContact() throws ReflectiveOperationException {
        assertTrue(proves(rails(57, 72, true, false)));
    }

    @Test
    public void rejectsContactThatClosesOneEnd() throws ReflectiveOperationException {
        assertFalse(proves(rails(45, 65, true, false)));
    }

    @Test
    public void rejectsMissingUpperCoreAndIndependentThirdRail() throws ReflectiveOperationException {
        assertFalse(proves(rails(57, 72, false, false)));
        assertFalse(proves(rails(57, 72, true, true)));
    }

    private static byte[] rails(int contactFrom, int contactTo, boolean fullSecond, boolean third) {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int x = 40; x <= 88; x++) {
            int y = Math.round(121 + (x - 40) / 8f);
            fillColumn(ink, x, y - 2, y + 2);
            if (fullSecond || x < 58 || x > 70) fillColumn(ink, x, y - 14, y - 10);
            if (third) fillColumn(ink, x, y - 26, y - 22);
            if (x >= contactFrom && x <= contactTo) fillColumn(ink, x, y - 11, y - 1);
        }
        return ink;
    }

    private static void fillColumn(byte[] ink, int x, int top, int bottom) {
        for (int y = top; y <= bottom; y++) ink[y * WIDTH + x] = 0;
    }

    private static boolean proves(byte[] ink) throws ReflectiveOperationException {
        var method = ClosedSixteenthDyads.class.getDeclaredMethod(
                "railsWithContactingMiddle",
                ClosedSixteenthDyads.Ink.class,
                PrintedBeamShafts.Shaft.class,
                PrintedBeamShafts.Shaft.class,
                float.class);
        method.setAccessible(true);
        var first = new PrintedBeamShafts.Shaft(39, 41, 66, 126, 1, List.of());
        var second = new PrintedBeamShafts.Shaft(87, 89, 66, 134, 1, List.of());
        return (boolean) method.invoke(
                null, new ClosedSixteenthDyads.Ink(ink, WIDTH, HEIGHT), first, second, GAP);
    }
}
