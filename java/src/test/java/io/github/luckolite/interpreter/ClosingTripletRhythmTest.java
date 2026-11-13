// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosingTripletRhythmTest {
    @Test
    public void requiresAllFiveStaffRulesToEndTogether() {
        int width = 320, height = 200, end = 230, bottom = 132, gap = 14;
        var bar = new MeasureRegion(.1f, end / (float) width, .25f, .8f);
        var track = StaffPitchTrack.fromVerifiedSamples(
                List.of(new float[] {20, bottom, gap}, new float[] {end, bottom, gap}));
        var raster = new byte[width * height];
        Arrays.fill(raster, (byte) 255);
        assertFalse(ClosingTripletRhythm.printedEnd(raster, width, height, bar, track, gap));

        for (int line = 0; line < 5; line++) {
            int row = (bottom - line * gap) * width;
            Arrays.fill(raster, row + 30, row + end + 1, (byte) 0);
        }
        assertTrue(ClosingTripletRhythm.printedEnd(raster, width, height, bar, track, gap));

        int middle = (bottom - 2 * gap) * width;
        Arrays.fill(raster, middle + end - 6, middle + end + 1, (byte) 255);
        assertFalse(ClosingTripletRhythm.printedEnd(raster, width, height, bar, track, gap));

        Arrays.fill(raster, middle + end - 6, middle + end + 28, (byte) 0);
        assertFalse(ClosingTripletRhythm.printedEnd(raster, width, height, bar, track, gap));
    }
}
