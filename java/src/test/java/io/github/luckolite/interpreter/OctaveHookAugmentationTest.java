// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class OctaveHookAugmentationTest {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 220;

    @Test
    public void onlyACompleteDownwardDottedRibbonOwnsTheMarkBesideTheHead() {
        byte[] source = new byte[WIDTH * HEIGHT];
        Arrays.fill(source, (byte) 255);
        for (int x = 163; x <= 223; x += 10) fill(source, x, x + 5, 81, 84, 0);
        fill(source, 228, 233, 81, 84, 0);
        for (int y : new int[] {90, 98, 107}) fill(source, 230, 233, y, y + 4, 0);
        int axis = owner(source);
        assertTrue(axis >= 230 && axis <= 233);

        byte[] noEnd = source.clone();
        fill(noEnd, 228, 235, 106, 113, 255);
        assertEquals(-1, owner(noEnd));

        byte[] unrelated = source.clone();
        fill(unrelated, 163, 227, 81, 84, 255);
        assertEquals(-1, owner(unrelated));
    }

    private static int owner(byte[] gray) {
        return OctaveMarkDetector.hookedOctaveDashBesideHead(
                gray, WIDTH, HEIGHT, 230, 198, 110, 16f);
    }

    private static void fill(byte[] gray, int left, int right, int first, int last, int value) {
        for (int y = first; y <= last; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
