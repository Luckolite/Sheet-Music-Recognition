// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class SteppedOctaveBeamClosureTest {
    private static final int WIDTH = 238;
    private static final int HEIGHT = 225;
    private static final float GAP = 16;
    private static final int[] STEM = {43, 83, 122, 164};
    private static final int[] TOP = {60, 64, 70, 77};

    @Test
    public void followsNarrowPaperSeamAcrossAnOwnedSteppedShaft() {
        assertTrue(proves(fixture(false,false)));
    }

    @Test
    public void requiresBothShaftOverlapAndAnOpenSeam() {
        assertFalse(proves(fixture(true,false)));
        assertFalse(proves(fixture(false,true)));
    }

    private static boolean proves(byte[] gray) {
        var heads = new ClosedFourDyadBeam.Head[STEM.length];
        for (int i = 0; i < heads.length; i++) {
            heads[i] = new ClosedFourDyadBeam.Head(STEM[i] + 11, TOP[i], TOP[i] + 56);
        }
        return ClosedFourDyadBeam.provesOctavesWithSteppedStem(gray, WIDTH, HEIGHT, heads, GAP, 155, 1, 2);
    }

    private static byte[] fixture(boolean eraseJoint, boolean mergeRails) {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte)255);
        for (int x = STEM[0]; x <= STEM[3]; x++) {
            int outer = edge(x);
            int seam = x < STEM[1] ? outer - 10 : outer - 9;
            for (int y = outer - 21; y <= outer; y++) {
                if (y != seam || mergeRails) ink[y * WIDTH + x] = 0;
            }
        }
        for (int i = 0; i < STEM.length; i++) {
            int x = STEM[i];
            int top = TOP[i];
            if (i == 2) {
                line(ink, x + 1, top + 3, top + 71);
                line(ink, x, top + 59, edge(x));
                if (eraseJoint) {
                    for (int y = top + 62; y < top + 70; y++) {
                        ink[y * WIDTH + x] = (byte)255;
                        ink[y * WIDTH + x + 1] = (byte)255;
                    }
                }
            } else {
                line(ink, x, top + 3, edge(x));
            }
        }
        return ink;
    }

    private static int edge(int x) {
        return 169 + Math.round((x - STEM[0]) * .14f);
    }

    private static void line(byte[] ink, int x, int top, int bottom) {
        for (int y = top; y <= bottom; y++) ink[y * WIDTH + x] = 0;
    }
}
