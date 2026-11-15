// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.Test;

public final class ClosedBeamCadenceTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 260;
    private static final int GAP = 16;
    private static final int[] PRECEDING = {140, 200, 1};
    private static final int[] TERMINAL = {180, 200, 1};
    private static final int[] BORROWED = {182, 90, -1};

    @Test
    public void closedBeamOwnsTheMaskAtItsClosingShaft() {
        assertTrue(proves(closingBeam(), fragment()));
    }

    @Test
    public void aContinuingBeamIsNotAClosedGroup() {
        byte[] gray = closingBeam();
        ink(gray, 180, 205, 176, 180);
        ink(gray, 180, 205, 188, 192);
        assertFalse(proves(gray, fragment()));
    }

    @Test
    public void maskInkBeyondTheBeamKeepsAnIndependentBody() {
        byte[] gray = closingBeam();
        ink(gray, 172, 172, 197, 197);
        int[] part =
                IntStream.concat(Arrays.stream(fragment()), IntStream.of(197 * WIDTH + 172))
                        .toArray();
        assertFalse(proves(gray, part));
    }

    private static boolean proves(byte[] gray, int[] mask) {
        return TerminalBeamShaftEnd.provesMergedBeforeCadence(
                gray, WIDTH, GAP, mask, BORROWED, PRECEDING, TERMINAL);
    }

    private static byte[] closingBeam() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        ink(gray, 140, 183, 176, 180);
        ink(gray, 140, 183, 188, 192);
        ink(gray, 170, 183, 181, 187);
        return gray;
    }

    private static int[] fragment() {
        return IntStream.rangeClosed(188, 192)
                .flatMap(y -> IntStream.rangeClosed(170, 183).map(x -> y * WIDTH + x))
                .toArray();
    }

    private static void ink(byte[] gray, int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
        }
    }
}
