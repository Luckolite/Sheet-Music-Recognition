// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class WrappedOpeningRailEvidenceTest {
    private static final int WIDTH = 210;
    private static final int HEIGHT = 190;
    private static final int GAP = 16;

    @Test
    public void anExtensionAboveTheMiddleShaftKeepsTheCommonRail() {
        var field = new Field();
        var shaft = field.trim();
        assertNotNull(shaft);
        assertEquals(45, shaft.endY());
    }

    @Test
    public void theExtendingInkCannotReplaceAContinuousRail() {
        var field = new Field();
        for (int y = 39; y < 64; y++) {
            Arrays.fill(field.raw, y * WIDTH + 73, y * WIDTH + 88, (byte) 255);
        }
        assertNull(field.trim());
    }

    @Test
    public void theReconstructedEndpointMustTouchTheMiddleShaft() {
        var field = new Field();
        for (int y = 44; y <= 54; y++) {
            field.raw[y * WIDTH + 104] = (byte) 255;
            field.raw[y * WIDTH + 105] = (byte) 255;
        }
        assertNull(field.trim());
    }

    private static final class Field {
        final byte[] raw = new byte[WIDTH * HEIGHT];
        final PrintedBeamShafts.Shaft first = shaft(60, 114, 50);
        final PrintedBeamShafts.Shaft middle = shaft(105, 99, 30);
        final PrintedBeamShafts.Shaft last = shaft(150, 84, 40);

        Field() {
            Arrays.fill(raw, (byte) 255);
            for (int axis = 59; axis <= 150; axis++) {
                int cap = 50 - Math.round((axis - 60) / 9f);
                for (int y = cap; y <= cap + 7; y++) {
                    raw[y * WIDTH + axis] = 0;
                }
            }
            for (var shaft : List.of(first, middle, last)) {
                for (int y = shaft.endY(); y <= shaft.startY(); y++) {
                    raw[y * WIDTH + shaft.left()] = 0;
                    raw[y * WIDTH + shaft.right()] = 0;
                }
            }
        }

        private static PrintedBeamShafts.Shaft shaft(int x, int start, int end) {
            return new PrintedBeamShafts.Shaft(x - 1, x, start, end, -1, List.of());
        }

        PrintedBeamShafts.Shaft trim() {
            return WrappedEighthTriplets.trimExtendedMiddleShaft(
                    raw, WIDTH, HEIGHT, first, middle, last, GAP);
        }
    }
}
