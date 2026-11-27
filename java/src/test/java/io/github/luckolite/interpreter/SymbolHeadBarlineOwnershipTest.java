// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SymbolHeadBarlineOwnershipTest {
    private static final int WIDTH = 180, HEIGHT = 160, X = 80, GAP = 14;

    @Test
    public void halfOfCrossingTieDoesNotOwnThePrintedRule() throws Exception {
        byte[] labels = new byte[WIDTH * HEIGHT];
        for (int offset = 1; offset <= 22; offset++) {
            int center = 105 - offset / 3;
            for (int dy = -2; dy <= 2; dy++) symbol(labels, X + offset, center + dy);
        }
        assertFalse(ownsColumn(labels));
    }

    @Test
    public void filledAndHollowDiamondsStillOwnTheStem() throws Exception {
        for (boolean hollow : new boolean[] {false, true}) {
            byte[] labels = new byte[WIDTH * HEIGHT];
            for (int dy = -7; dy <= 7; dy++)
                for (int dx = -10; dx <= 10; dx++) {
                    float radius = Math.abs(dx) / 10f + Math.abs(dy) / 7f;
                    if (radius <= 1f && (!hollow || radius >= .43f))
                        symbol(labels, X + 10 + dx, 91 + dy);
                }
            assertTrue(ownsColumn(labels));
        }
    }

    private static void symbol(byte[] labels, int x, int y) {
        labels[y * WIDTH + x] = OmrMeasurePostProcessor.SYMBOL;
    }

    private static boolean ownsColumn(byte[] labels) throws Exception {
        var ownership = OmrMeasurePostProcessor.class.getDeclaredMethod(
                "symbolHeadTouchesColumn", byte[].class, int.class, int.class,
                int.class, int.class, int.class, float.class);
        ownership.setAccessible(true);
        return (boolean) ownership.invoke(null, labels, WIDTH, HEIGHT, X, 56, 112, (float) GAP);
    }
}
