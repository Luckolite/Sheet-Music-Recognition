// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class SemanticBarlineHaloTest {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 168;
    private static final int[] ROWS = {43, 57, 71, 85, 99};

    @Test
    public void matchingRawStemOwnsTheNeighboringSemanticHalo() throws ReflectiveOperationException {
        assertEquals(List.of(12, 243), boundaries(true));
    }

    @Test
    public void independentRuleKeepsItsBoundaryWithoutAConnectedHead() throws ReflectiveOperationException {
        assertEquals(List.of(12, 131, 243), boundaries(false));
    }

    private static Object boundaries(boolean addHead) throws ReflectiveOperationException {
        byte[] gray = new byte[WIDTH * HEIGHT];
        byte[] labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 247);
        for (int y : ROWS)
            for (int x = 12; x < 244; x++) ink(gray, labels, x, y + 2, 4, 150);
        for (int y = 41; y <= 103; y++) {
            for (int x = 130; x <= 132; x++) ink(gray, labels, x, y, 1, 70);
            ink(gray, labels, 132, y, 1, 170);
        }
        for (int x = 130; x <= 183; x++) {
            for (int y = 41; y <= 45; y++) ink(gray, labels, x, y, 5, 14);
            for (int y = 52; y <= 55; y++) ink(gray, labels, x, y, 5, 14);
        }
        if (addHead)
            for (int y = 96; y <= 106; y++)
                for (int x = 111; x <= 135; x++) {
                    float dx = (x - 123 - (y - 101) * .5f) / 12, dy = (y - 101) / 5f;
                    if (dx * dx + dy * dy <= 1) ink(gray, labels, x, y, 2, 43);
                }
        var find = OmrMeasurePostProcessor.class.getDeclaredMethod("findBoundaries", byte[].class, byte[].class,
                int.class, int.class, int[].class, float.class, int.class, int.class, float.class);
        find.setAccessible(true);
        return find.invoke(null, labels, gray, WIDTH, HEIGHT, ROWS, 14f, 12, 243, 0f);
    }

    private static void ink(byte[] gray, byte[] labels, int x, int y, int label, int tone) {
        gray[y * WIDTH + x] = (byte) tone;
        labels[y * WIDTH + x] = (byte) label;
    }
}
