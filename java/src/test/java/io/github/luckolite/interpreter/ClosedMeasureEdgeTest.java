// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class ClosedMeasureEdgeTest {
    private static final int WIDTH = 360, HEIGHT = 180, TOP = 48, BOTTOM = 112;
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    public ClosedMeasureEdgeTest() {
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += 16)
            Arrays.fill(gray, y * WIDTH, (y + 1) * WIDTH, (byte) 0);
    }

    @Test
    public void paddedEdgeRequiresRealBoundaryInk() {
        var region = new MeasureRegion(30f / WIDTH, 330f / WIDTH, 15f / HEIGHT, 146f / HEIGHT);
        stroke(22, TOP, BOTTOM);
        stroke(337, TOP, BOTTOM);
        assertTrue(WrappedMeasureBoundaries.confirmsClosed(gray, WIDTH, HEIGHT, region, 46, 300));

        stroke(337, TOP - 20, BOTTOM + 20);
        assertFalse(WrappedMeasureBoundaries.confirmsClosed(gray, WIDTH, HEIGHT, region, 46, 300));
    }

    @Test
    public void imageEdgesRequireInBoundsInkOnBothSides() {
        var region = new MeasureRegion(0, 1, 15f / HEIGHT, 146f / HEIGHT);
        stroke(0, TOP, BOTTOM);
        assertFalse(WrappedMeasureBoundaries.confirmsClosed(gray, WIDTH, HEIGHT, region, 28, 330));

        stroke(WIDTH - 3, TOP, BOTTOM);
        assertTrue(WrappedMeasureBoundaries.confirmsClosed(gray, WIDTH, HEIGHT, region, 28, 330));
    }

    private void stroke(int column, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + column, y * WIDTH + column + 3, (byte) 0);
    }
}
