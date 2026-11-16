// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class RestCompetingGlyphsTest {
    private static final int WIDTH = 180, HEIGHT = 150;

    @Test
    public void isolatedThreeAndOneNeedTwoSeparatePrintedComponents() {
        var paper = new byte[WIDTH * HEIGHT];
        Arrays.fill(paper, (byte) 255);
        stripe(paper, 68, 43, 78, 45);
        stripe(paper, 76, 46, 80, 49);
        stripe(paper, 71, 50, 77, 52);
        stripe(paper, 76, 53, 80, 57);
        stripe(paper, 68, 58, 78, 61);
        stripe(paper, 71, 67, 76, 70);
        stripe(paper, 74, 71, 76, 81);
        stripe(paper, 70, 81, 78, 83);
        assertTrue(matches(paper));

        var joined = paper.clone();
        stripe(joined, 75, 61, 76, 67);
        assertFalse(matches(joined));

        var noThree = paper.clone();
        for (int row = 41; row <= 62; row++)
            Arrays.fill(noThree, row * WIDTH + 66, row * WIDTH + 81, (byte) 255);
        assertFalse(matches(noThree));
    }

    private static boolean matches(byte[] paper) {
        return RestCompetingGlyphs.threeThenOne(paper, WIDTH, HEIGHT, 74, 64, 78, 16);
    }

    private static void stripe(byte[] paper, int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(paper, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
