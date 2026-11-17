// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class InteriorOpeningRailTest {
    private static final int WIDTH = 140;
    private static final int HEIGHT = 160;
    private static final float GAP = 16;

    @Test
    public void theExactMaskBelongsToTheMiddleShaftsSlopingRail() throws Exception {
        byte[] labels = mask(0);
        byte[] gray = source(labels);
        assertTrue(proves(labels, gray, 45));
        assertFalse(proves(labels, gray, 49));
    }

    @Test
    public void aSeparateBodyOrUnprintedPixelsCannotClaimTheRail() throws Exception {
        byte[] separate = mask(16);
        assertFalse(proves(separate, source(separate), 45));
        byte[] labels = mask(0);
        byte[] gray = source(labels);
        gray[43 * WIDTH + 58] = (byte) 255;
        assertFalse(proves(labels, gray, 45));
    }

    private static boolean proves(byte[] labels, byte[] gray, int borrowedShaft) throws Exception {
        var owner = OmrScoreInterpreter.class;
        var components = owner.getDeclaredMethod("findComponents", byte[].class, int.class,
                int.class, byte.class);
        components.setAccessible(true);
        var found = (List<?>) components.invoke(null, labels, WIDTH, HEIGHT, (byte) 2);
        var mask = found.get(0);
        var proof = owner.getDeclaredMethod("maskOnInteriorUpstemRail", byte[].class,
                byte[].class, int.class, int.class, mask.getClass(), int.class,
                PrintedBeamShafts.Shaft[].class, float.class);
        proof.setAccessible(true);
        var rail = new PrintedBeamShafts.Shaft[] {
                shaft(14, 53), shaft(45, 43), shaft(88, 30)
        };
        return (boolean) proof.invoke(null, labels, gray, WIDTH, HEIGHT, mask, borrowedShaft, rail, GAP);
    }

    private static PrintedBeamShafts.Shaft shaft(int x, int end) {
        return new PrintedBeamShafts.Shaft(x, x, end + 55, end, -1, List.of());
    }

    private static byte[] mask(int offset) {
        byte[] plane = new byte[WIDTH * HEIGHT];
        for (int x = 47; x <= 64; x++) {
            int y = 45 + offset - (x - 45) / 3;
            for (int p = y; p < y + 6; p++) plane[p * WIDTH + x] = 2;
        }
        return plane;
    }

    private static byte[] source(byte[] labels) {
        byte[] gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == 2) gray[i] = 0;
        }
        return gray;
    }
}
