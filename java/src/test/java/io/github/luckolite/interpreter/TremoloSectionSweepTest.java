// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;

public class TremoloSectionSweepTest {
    @Test
    public void steppedBarsCrossingStaffLinesKeepTheirPixelsAndBudgetBoundary() {
        int width = 68, height = 60;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        var staff = new HashSet<Integer>();
        for (int y : new int[] {19, 26, 33, 40}) {
            for (int x = 6; x <= 61; x++) {
                gray[y * width + x] = 50;
                staff.add(y * width + x);
            }
        }
        for (int x = 13; x <= 37; x++) {
            int rise = (x - 13) / 6;
            for (int dy = 0; dy < 3; dy++) {
                gray[(20 + rise + dy) * width + x] = 35;
                gray[(29 + rise + dy) * width + x] = 35;
            }
        }
        var lane = new MaximalRawBarExtractor.Lane(6, 61, 10, 48, 12);
        var complete = MaximalRawBarExtractor.discoverJoined(gray, width, height, lane,
                2_000_000, MaximalRawBarExtractor.MAX_SCRATCH_BYTES, staff);
        assertEquals(MaximalRawBarExtractor.Status.CANDIDATES, complete.status());
        assertEquals(447_521, complete.pixelWork());
        assertEquals(2, complete.bodies().size());
        assertEquals("49e04cb333ac9553b7631864053e9fb3c701ead84ee5d355a2a5f868855af364", complete.bodiesSha256());
        assertEquals(
                complete,
                MaximalRawBarExtractor.discoverForMarks(
                        gray, width, height, lane, 2_000_000, staff, true));

        var refused = MaximalRawBarExtractor.discoverJoined(gray, width, height, lane,
                173_401, MaximalRawBarExtractor.MAX_SCRATCH_BYTES, staff);
        assertEquals(MaximalRawBarExtractor.Status.BUDGET_EXHAUSTED, refused.status());
        assertEquals(173_137, refused.pixelWork());
        assertTrue(refused.bodies().isEmpty());

        for (int x = 13; x <= 37; x++) {
            int rise = (x - 13) / 6;
            for (int dy = 0; dy < 3; dy++) {
                int pixel = (29 + rise + dy) * width + x;
                gray[pixel] = (byte) (staff.contains(pixel) ? 50 : 255);
            }
        }
        var single = MaximalRawBarExtractor.discoverJoined(gray, width, height, lane,
                2_000_000, MaximalRawBarExtractor.MAX_SCRATCH_BYTES, staff);
        var markSingle = MaximalRawBarExtractor.discoverForMarks(
                gray, width, height, lane, 2_000_000, staff, true);
        assertEquals(1, single.bodies().size());
        assertNotNull(single.sourceIdentity());
        assertNull(markSingle.sourceIdentity());
        assertEquals(single.bodies(), markSingle.bodies());
        assertEquals(single.status(), markSingle.status());
        assertEquals(single.pixelWork(), markSingle.pixelWork());
        assertEquals(single.bodiesSha256(), markSingle.bodiesSha256());
    }

    @Test
    public void emptyMarkDiscoveryKeepsTheSearchWorkWithoutBindingAnIdentity() {
        int width = 68, height = 60;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        var lane = new MaximalRawBarExtractor.Lane(6, 61, 10, 48, 12);
        long budget = 2_000_000;
        var regular = MaximalRawBarExtractor.discover(gray, width, height, lane, budget);
        var marks =
                MaximalRawBarExtractor.discoverForMarks(
                        gray, width, height, lane, budget, null, false);

        assertEquals(MaximalRawBarExtractor.Status.CANDIDATES, marks.status());
        assertTrue(marks.bodies().isEmpty());
        assertNotNull(regular.sourceIdentity());
        assertNull(marks.sourceIdentity());
        assertEquals(regular.status(), marks.status());
        assertEquals(regular.pixelWork(), marks.pixelWork());
        assertEquals(regular.terminalStage(), marks.terminalStage());
        assertEquals(regular.bodiesSha256(), marks.bodiesSha256());
    }
}
