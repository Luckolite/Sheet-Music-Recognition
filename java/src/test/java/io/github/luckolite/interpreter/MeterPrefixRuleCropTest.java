// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;

public final class MeterPrefixRuleCropTest {
    private static final int WIDTH = 124, HEIGHT = 116;
    private static final MeterChangeDetector.Crop METER =
            new MeterChangeDetector.Crop(30, 20, 78, 98, 22, 16);

    @Test
    public void independentSingleRuleCanBeTrimmedWithoutDroppingNumeralInk() {
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        paint(page, 34, 22, 37, 86, 0);
        paint(page, 45, 29, 58, 46, 0);
        paint(page, 46, 57, 59, 81, 0);
        assertEquals(38, trim(page).left());
        assertSame(METER, MeterDoubleRuleCrop.trim(METER, page, WIDTH, HEIGHT, 38));

        var interrupted = page.clone();
        paint(interrupted, 34, 46, 37, 51, 255);
        assertSame(METER, trim(interrupted));

        paint(page, 38, 40, 46, 52, 0);
        assertSame(METER, trim(page));
    }

    private static MeterChangeDetector.Crop trim(byte[] page) {
        return MeterDoubleRuleCrop.trim(METER, page, WIDTH, HEIGHT, 45);
    }

    private static void paint(byte[] page, int left, int top, int right, int bottom, int shade) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) shade);
    }
}
