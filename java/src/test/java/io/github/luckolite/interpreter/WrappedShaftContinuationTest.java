// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class WrappedShaftContinuationTest {
    private static final int WIDTH = 200, HEIGHT = 180, GAP = 16;

    @Test
    public void missingOuterShaftJoinsTheIndependentSingleRail() {
        var field = new Field();
        field.eraseFirstShaft(77, 80);
        var resolved = field.proof();
        assertNotNull(resolved);
        assertEquals(0, resolved.revised());
        assertEquals(50, resolved.shafts()[0].endY());
    }

    @Test
    public void aTrueGapCannotBorrowTheNeighborsBeam() {
        var field = new Field();
        field.eraseFirstShaft(75, 87);
        assertNull(field.proof());
    }

    @Test
    public void aSecondPrintedRailCannotProveTheSingleBeam() {
        var field = new Field();
        field.rail(13);
        assertNull(field.proof());
    }

    @Test
    public void staffRuleNearOneBeamEndKeepsRecoveredTripletGroupsSeparate() {
        int width = 200, height = 240, gap = 16;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int line = 90; line <= 154; line += 16) {
            for (int dy = 0; dy < 4; dy++) {
                Arrays.fill(gray, (line + dy) * width + 10, (line + dy) * width + 190, (byte) 0);
            }
        }
        var bar = new MeasureRegion(15f / width, 180f / width, 56f / height, 187f / height);
        var left = new PrintedBeamShafts.Shaft(64, 65, 51, 127, 1, List.of());
        var right = new PrintedBeamShafts.Shaft(99, 100, 80, 145, 1, List.of());
        for (int y = 51; y <= 127; y++) {
            gray[y * width + 64] = gray[y * width + 65] = 0;
        }
        for (int y = 80; y <= 145; y++) {
            gray[y * width + 99] = gray[y * width + 100] = 0;
        }
        assertFalse(WrappedEighthTriplets.matchesRailBridge(
                gray, width, height, left, right, gap, false));
        assertTrue(WrappedEighthTriplets.cutBetweenRecoveredGroups(
                gray, width, height, bar, left, right, gap));

        for (int x = 65; x <= 100; x++) {
            int center = Math.round(127 + (x - 65) * 18f / 35);
            for (int y = center - 2; y <= center + 2; y++) gray[y * width + x] = 0;
        }
        assertFalse(WrappedEighthTriplets.cutBetweenRecoveredGroups(
                gray, width, height, bar, left, right, gap));
    }

    private static final class Field {
        final byte[] raw = new byte[WIDTH * HEIGHT];
        final float[] x = {50, 94, 138};
        final float[] y = {112, 97, 82};
        final PrintedBeamShafts.Shaft[] modeled = {null, shaft(104, 97, 45), shaft(148, 82, 40)};

        Field() {
            Arrays.fill(raw, (byte) 255);
            for (int j = 0; j < 3; j++) {
                int axis = (int) x[j] + 10, end = 50 - j * 5;
                for (int py = end; py <= (int) y[j]; py++) {
                    raw[py * WIDTH + axis] = 0;
                    raw[py * WIDTH + axis - 1] = 0;
                }
            }
            rail(0);
        }

        private static PrintedBeamShafts.Shaft shaft(int x, int start, int cap) {
            return new PrintedBeamShafts.Shaft(x - 1, x, start, cap, -1, List.of());
        }

        void rail(int shift) {
            for (int axis = 59; axis <= 149; axis++) {
                int start = 50 - Math.round((axis - 59) / 8.8f);
                for (int yy = start + shift; yy <= start + shift + 7; yy++)
                    raw[yy * WIDTH + axis] = 0;
            }
        }

        void eraseFirstShaft(int top, int bottom) {
            for (int yy = top; yy <= bottom; yy++) {
                raw[yy * WIDTH + 59] = (byte) 255;
                raw[yy * WIDTH + 60] = (byte) 255;
            }
        }

        WrappedShaftContinuation.Packed proof() {
            return WrappedShaftContinuation.recoverGroup(
                    raw, WIDTH, HEIGHT, GAP, x, y, -1, modeled);
        }
    }
}
