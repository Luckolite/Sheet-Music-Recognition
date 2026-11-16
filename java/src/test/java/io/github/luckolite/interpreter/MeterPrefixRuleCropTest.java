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

    @Test
    public void bothThickStrokesMustBelongToTheSameStaffBoundedRule() {
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        paint(page, 34, 22, 39, 86, 0);
        paint(page, 42, 22, 47, 86, 0);
        paint(page, 50, 29, 62, 46, 0);
        paint(page, 51, 57, 63, 81, 0);
        assertEquals(48, trim(page).left());

        var unpaired = page.clone();
        paint(unpaired, 42, 22, 47, 86, 255);
        assertSame(METER, trim(unpaired));

        var beyondStaff = page.clone();
        paint(beyondStaff, 42, 86, 47, 102, 0);
        assertSame(METER, trim(beyondStaff));

        var noSeparator = page.clone();
        paint(noSeparator, 48, 37, 49, 62, 0);
        assertSame(METER, trim(noSeparator));
    }

    @Test
    public void pairedRuleCanShedAConnectedTaperBeforeTheNumerals() {
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        paint(page, 33, 22, 35, 86, 0);
        paint(page, 40, 22, 42, 86, 0);
        paint(page, 43, 22, 43, 70, 0);
        paint(page, 44, 38, 44, 61, 0);
        paint(page, 45, 43, 45, 57, 0);
        paint(page, 49, 28, 61, 47, 0);
        paint(page, 50, 59, 62, 79, 0);
        assertEquals(46, trim(page).left());

        var detached = page.clone();
        paint(detached, 45, 26, 45, 37, 0);
        assertSame(METER, trim(detached));

        var single = page.clone();
        paint(single, 33, 22, 35, 86, 255);
        assertSame(METER, trim(single));
    }

    private static MeterChangeDetector.Crop trim(byte[] page) {
        return MeterDoubleRuleCrop.trim(METER, page, WIDTH, HEIGHT, 45);
    }

    private static void paint(byte[] page, int left, int top, int right, int bottom, int shade) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) shade);
    }
}
