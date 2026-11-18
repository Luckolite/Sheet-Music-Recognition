// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class TieShoulderWindowTest {
    private static final int WIDTH = 120, HEIGHT = 80;
    private final byte[] labels = new byte[WIDTH * HEIGHT], gray = new byte[labels.length];

    public TieShoulderWindowTest() {
        Arrays.fill(gray, (byte) 255);
    }

    private TieShoulderWindow window() {
        return TieShoulderWindow.create(labels, gray, WIDTH, HEIGHT, 40, 10, 0, 5, 115);
    }

    @Test
    public void aDarkRunIsARuleOnlyForSpansItCovers() {
        Arrays.fill(gray, 40 * WIDTH + 15, 40 * WIDTH + 96, (byte) 80);
        var window = window();
        assertTrue(window.excludes(15, 95));
        assertTrue(window.excludesShorterSpans(15, 80, true));
        assertTrue(window.excludesShorterSpans(95, 80, false));
        assertFalse(window.excludes(14, 96));
        assertFalse(window.excludesShorterSpans(14, 82, true));
    }

    @Test
    public void palestEligiblePixelCanKeepEachShoulderAlive() {
        int a = 24, b = 100;
        gray[40 * WIDTH + a] = gray[40 * WIDTH + b] = (byte) 225;
        assertFalse(window().excludes(a, b));
        labels[40 * WIDTH + b] = OmrMeasurePostProcessor.NOTEHEAD;
        assertTrue(window().excludes(a, b));
        labels[40 * WIDTH + b] = 0;
        gray[40 * WIDTH + a] = (byte) 226;
        assertTrue(window().excludes(a, b));
    }

    @Test
    public void faintShoulderInAnyByteLaneKeepsInsetFamilyAlive() {
        for (int offset = 0; offset < 8; offset++) {
            Arrays.fill(gray, (byte) 226);
            // The first possible foreground byte follows a different bright prefix on each pass.
            gray[HEIGHT / 2 * WIDTH + 13 + offset] = (byte) 225;
            gray[HEIGHT / 2 * WIDTH + 106] = (byte) 225;
            assertFalse(window().excludesInsets(5));
            gray[HEIGHT / 2 * WIDTH + 106] = (byte) 226;
            assertTrue(window().excludesInsets(5));
        }
    }

    @Test
    public void uncoveredSpansUseNormalCurveProof() {
        assertFalse(window().excludes(0, 95));
        assertFalse(window().excludesShorterSpans(95, 92, false));
    }
}
