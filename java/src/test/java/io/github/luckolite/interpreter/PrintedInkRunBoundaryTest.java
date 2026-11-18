// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PrintedInkRunBoundaryTest {
    @Test
    public void joinsAcrossWordsAndClipsAfterEarlyStopOnReusedScratch() {
        int width = 260;
        byte[] gray = new byte[width * 5];
        Arrays.fill(gray, (byte) 255);
        Arrays.fill(gray, width + 1, width + 195, (byte) 149);
        gray[2 * width + 195] = (byte) 149;
        gray[3 * width + 1] = (byte) 149;
        var components = new PrintedInkComponents();
        assertFalse(components.scan(gray, width, 1, 1, 257, 5, 149,
                (left, top, right, bottom, count) -> false));

        var boxes = new ArrayList<List<Integer>>();
        assertTrue(components.scan(gray, width, 1, 1, 196, 5, 149,
                (left, top, right, bottom, count) -> boxes.add(List.of(left, top, right, bottom, count))));
        assertEquals(List.of(List.of(1, 1, 196, 3, 195), List.of(1, 3, 2, 4, 1)), boxes);
    }
}
