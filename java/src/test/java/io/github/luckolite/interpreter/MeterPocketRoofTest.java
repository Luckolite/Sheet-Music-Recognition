// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import org.junit.Test;

public class MeterPocketRoofTest {
    @Test public void diagonalOpeningAtEndOfRunLeavesAdjacentPocketDiscoverable() {
        int width = 19, left = 3, top = 2, w = 12, h = 5;
        byte[] gray = new byte[width * 10];
        Arrays.fill(gray, (byte) 160);
        gray[top * width + left + 5] = gray[(top + 1) * width + left + 5] = (byte) 161;
        int row = (top + 2) * width + left;
        Arrays.fill(gray, row + 2, row + 5, (byte) 161);
        Arrays.fill(gray, row + 8, row + 10, (byte) 161);
        int seed = MeterCounterFlood.nextRoof(gray, width, left, top, w, h, 0);
        assertEquals(3 * (w + 2) + 9, seed);
        assertEquals(-1, MeterCounterFlood.nextRoof(gray, width, left, top, w, h, seed + 1));
    }
}
