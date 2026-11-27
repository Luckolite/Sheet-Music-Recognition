// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class RawNumeralComponentOrderTest {
    @Test
    public void croppedWindowKeepsBreadthFirstPixelOrderAndCharges() {
        byte[] page = new byte[60];
        Arrays.fill(page, (byte) 255);
        for (int pixel : new int[] {12, 23, 24, 32, 36}) page[pixel] = (byte) 165;
        int[] ticks = {0};
        var newAt = new ArrayList<Integer>();
        var graph = RawNumeralComponents.capture(
                page, 10, 6, 2, 1, 6, 3, 165, () -> ticks[0]++, () -> newAt.add(ticks[0]));
        assertEquals(List.of(1, 49), newAt);
        assertEquals(55, ticks[0]);
        assertEquals(2, graph.components().size());
        var first = graph.components().get(0);
        assertEquals(List.of(2, 1, 4, 3), List.of(first.left, first.top, first.right, first.bottom));
        assertEquals(4, first.size());
        assertEquals(List.of(12, 23, 24, 32),
                List.of(first.pixel(0), first.pixel(1), first.pixel(2), first.pixel(3)));
        assertEquals(36, graph.components().get(1).pixel(0));
    }

    @Test
    public void freezingTheLastPixelCanStillExhaustTheBudget() {
        byte[] page = {(byte) 255};
        int[] ticks = {0}, components = {0};
        var exhausted = new IllegalStateException("budget");
        try {
            RawNumeralComponents.capture(page, 1, 1, 0, 0, 0, 0, () -> {
                if (++ticks[0] == 1) page[0] = 0;
                if (ticks[0] == 4) throw exhausted;
            }, () -> components[0]++);
            fail("Expected the final coordinate charge");
        } catch (IllegalStateException actual) {
            assertSame(exhausted, actual);
        }
        assertEquals(1, components[0]);
        assertEquals(4, ticks[0]);
    }
}
