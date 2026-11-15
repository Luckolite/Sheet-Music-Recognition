// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class BeamedFlatBarlineTest {
    private static final int WIDTH = 275, HEIGHT = 300, FIRST_RULE = 130, COLUMN = 124;
    private static final float GAP = 16;

    @Test
    public void flatSpineCannotDivideTheBeamBetweenTwoDoubleStops() {
        var plate = new Plate();
        assertTrue(plate.alias());
    }

    @Test
    public void actualDividerAndDisconnectedBeamBothVetoTheJoin() {
        var divided = new Plate();
        divided.ink(COLUMN, COLUMN + 2, FIRST_RULE, FIRST_RULE + (int) (4 * GAP), (byte) 0);
        assertFalse(divided.alias());

        var detached = new Plate();
        for (int y = 121; y <= 145; y++)
            Arrays.fill(detached.gray, y * WIDTH + COLUMN - 2, y * WIDTH + COLUMN + 4, (byte) 255);
        assertFalse(detached.alias());
    }

    private static final class Plate {
        final byte[] labels = new byte[WIDTH * HEIGHT];
        final byte[] gray = new byte[WIDTH * HEIGHT];

        Plate() {
            Arrays.fill(gray, (byte) 255);
            for (int i = 0; i < 5; i++)
                ink(15, 260, FIRST_RULE + i * (int) GAP, FIRST_RULE + i * (int) GAP + 1, (byte) 4);
            for (int x = 98; x <= 163; x++) {
                int y = 122 + Math.round((x - 98) * .063f);
                ink(x, x, y, y + 8, (byte) 5);
                ink(x, x, y + 12, y + 20, (byte) 5);
            }
            for (int x : new int[] {90, 152}) {
                int stemX = x + 8;
                int tip = 122 + Math.round((stemX - 98) * .063f);
                ink(stemX, stemX + 2, tip, 195, (byte) 1);
                head(x, 177);
                head(x, 194);
            }
            ink(COLUMN, COLUMN + 2, 145, 189, (byte) 3);
            ink(COLUMN + 4, COLUMN + 9, 167, 170, (byte) 3);
            ink(COLUMN + 9, COLUMN + 11, 171, 178, (byte) 3);
            for (int y = 177; y <= 189; y++)
                ink(
                        COLUMN + Math.round((189 - y) * .75f),
                        COLUMN + Math.round((189 - y) * .75f) + 2,
                        y,
                        y,
                        (byte) 3);
        }

        boolean alias() {
            return BeamedFlatBarline.owns(
                    labels,
                    gray,
                    WIDTH,
                    HEIGHT,
                    COLUMN,
                    FIRST_RULE,
                    FIRST_RULE + (int) (4 * GAP),
                    GAP);
        }

        void head(int x, int y) {
            for (int dy = -8; dy <= 8; dy++) {
                int radius = Math.round(11f * (float) Math.sqrt(1 - dy * dy / 81f));
                ink(x - radius, x + radius, y + dy, y + dy, (byte) 2);
            }
        }

        void ink(int left, int right, int top, int bottom, byte label) {
            for (int y = top; y <= bottom; y++)
                for (int x = left; x <= right; x++) {
                    gray[y * WIDTH + x] = 0;
                    if (label != 0) labels[y * WIDTH + x] = label;
                }
        }
    }
}
