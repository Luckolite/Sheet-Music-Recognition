// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class DirectHarmonicRailTest {
    private static final int WIDTH = 128, HEIGHT = 120;
    private static final float GAP = 16;
    private static final PrintedBeamShafts.Shaft LEFT = shaft(28, 66, 1);
    private static final PrintedBeamShafts.Shaft RIGHT = shaft(77, 75, 1);

    @Test
    public void continuousThickRailConnectsTwoPhysicalShaftTips() throws Exception {
        byte[] ink = paper();
        paintRail(ink, 28, 77);
        assertTrue(joined(ink, LEFT, RIGHT));
        assertFalse(joined(ink, LEFT, shaft(77, 75, -1)));
    }

    @Test
    public void aStaffRuleAcrossThePhysicalSeamDoesNotJoinSeparateGroups() throws Exception {
        byte[] ink = paper();
        paintRail(ink, 28, 46);
        paintRail(ink, 57, 77);
        for (int x = 8; x < WIDTH - 8; x++) {
            ink[65 * WIDTH + x] = 0;
            ink[66 * WIDTH + x] = 0;
        }
        assertFalse(joined(ink, LEFT, RIGHT));
    }

    private static PrintedBeamShafts.Shaft shaft(int x, int end, int direction) {
        return new PrintedBeamShafts.Shaft(x - 1, x + 1, end - direction * 36, end, direction, List.of());
    }

    private static byte[] paper() {
        byte[] image = new byte[WIDTH * HEIGHT];
        Arrays.fill(image, (byte) 255);
        return image;
    }

    private static void paintRail(byte[] image, int left, int right) {
        for (int x = left; x <= right; x++) {
            int outer = Math.round(LEFT.endY()
                    + (RIGHT.endY() - LEFT.endY()) * (x - 28) / 49f);
            for (int dy = 0; dy <= 7; dy++) image[(outer - dy) * WIDTH + x] = 0;
        }
    }

    private static boolean joined(byte[] raw, PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b)
            throws Exception {
        var read = SystemQuarterCadence.class.getDeclaredMethod("directHarmonicRail",
                byte[].class, int.class, int.class,
                PrintedBeamShafts.Shaft.class, PrintedBeamShafts.Shaft.class, float.class);
        read.setAccessible(true);
        return (boolean) read.invoke(null, raw, WIDTH, HEIGHT, a, b, GAP);
    }
}
