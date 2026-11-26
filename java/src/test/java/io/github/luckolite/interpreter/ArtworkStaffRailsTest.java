// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class ArtworkStaffRailsTest {
    private static final int W = 480, H = 330, GAP = 13, MUSIC = 68, WIDE = 196;

    @Test
    public void aFullWidthSemanticStaffNeedsPhysicalRailsWhenAnotherStaffIsPresent() {
        var regions = measures(false);
        assertTrue(regions.stream().anyMatch(m -> m.top() < .5f));
        assertFalse(regions.stream().anyMatch(m -> m.top() > .5f));
    }

    @Test
    public void realFullWidthStaffWithOneMeasureIsRetained() {
        var regions = measures(true);
        assertTrue(regions.stream().anyMatch(m -> m.top() < .5f));
        assertTrue(regions.stream().anyMatch(m -> m.top() > .5f));
    }

    private static java.util.List<MeasureRegion> measures(boolean printedWide) {
        byte[] gray = new byte[W * H], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 253);
        for (int y = WIDE - 10; y < WIDE + 4 * GAP + 12; y++)
            for (int x = 0; x < W; x++)
                gray[y * W + x] = (byte) (208 + 8 * ((y / 13 + x / 21) % 4));
        staff(gray,labels,MUSIC,56,422,true);
        staff(gray,labels,WIDE,2,478,printedWide);
        return OmrMeasurePostProcessor.process(labels,gray,W,H);
    }

    private static void staff(byte[] gray,byte[] labels,int top,int left,int end,boolean printed) {
        for (int i = 0; i < 5; i++) {
            int row = (top + i * GAP) * W;
            Arrays.fill(labels,row+left,row+end,OmrMeasurePostProcessor.STAFF);
            if (printed) Arrays.fill(gray,row+left,row+end,(byte) 12);
        }
    }
}
