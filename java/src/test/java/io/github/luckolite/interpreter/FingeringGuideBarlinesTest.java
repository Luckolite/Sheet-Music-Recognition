// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class FingeringGuideBarlinesTest {
    private static final int W = 2000, H = 400;
    private static final List<MeasureRegion> UNSPLIT =
            List.of(new MeasureRegion(120f / W, 1880f / W, 195f / H, 326f / H));

    @Test
    public void twoProvedDividersAboveTheFingeringRulesRecoverThreeBars() {
        byte[] page = page();
        var bars = FingeringGuideBarlines.recover(UNSPLIT, page, page, W, H);
        assertEquals(3, bars.size());
        assertEquals(780f, (bars.get(0).right() + bars.get(1).left()) * W * .5f, 2f);
        assertEquals(1815f, (bars.get(1).right() + bars.get(2).left()) * W * .5f, 2f);
    }

    @Test
    public void bothPlanesMustProveTheUnjoinedGuideRules() {
        byte[] page = page(), raw = page.clone();
        for (int y = 264; y <= 284; y++) raw[y * W + 1815] = 0;
        assertSame(UNSPLIT, FingeringGuideBarlines.recover(UNSPLIT, page, raw, W, H));
    }

    private static byte[] page() {
        byte[] g = new byte[W * H];
        Arrays.fill(g, (byte) 255);
        for (int y : new int[] {200, 216, 232, 248, 264, 282, 301})
            for (int dy = -1; dy <= 1; dy++) Arrays.fill(g, (y + dy) * W + 100, (y + dy) * W + 1900, (byte) 0);
        for (int x : new int[] {780, 1815})
            for (int y = 199; y <= 265; y++) Arrays.fill(g, y * W + x - 1, y * W + x + 2, (byte) 0);
        return g;
    }
}
