// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class StaffOverlaidTripletRailsTest {
    private static final int WIDTH = 240;
    private static final int HEIGHT = 210;
    private static final int GAP = 16;

    @Test
    public void countsOnlyTheBeamAcrossTheStaff() throws ReflectiveOperationException {
        assertTrue(hasStaffSecondary(false, false));
    }

    @Test
    public void rejectsARealSecondBeamOrMissingPrimary() throws ReflectiveOperationException {
        assertFalse(hasStaffSecondary(true, false));
        assertFalse(hasStaffSecondary(false, true));
    }

    @Test
    public void anObliqueSearchBandMaySeeAStaffRuleButNotConsumeARealBeam() throws Exception {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = 72; y <= 136; y += GAP) fill(ink, 20, 219, y, y + 2, 0);
        var bar = new MeasureRegion(24f / WIDTH, 213f / WIDTH, 38f / HEIGHT, 170f / HEIGHT);
        var first = new PrintedBeamShafts.Shaft(74, 76, 60, 103, 1, List.of());
        var last = new PrintedBeamShafts.Shaft(109, 111, 60, 110, 1, List.of());
        var ribbon = Class.forName(WrappedEighthTriplets.class.getName() + "$Ribbon");
        var ctor = ribbon.getDeclaredConstructor(float.class, int.class, int.class);
        ctor.setAccessible(true);
        var observed = ctor.newInstance(.94f, 98, 110);
        var resolve = WrappedEighthTriplets.class.getDeclaredMethod("thinStaffRuleBetweenRails",
                byte[].class, int.class, int.class, MeasureRegion.class, PrintedBeamShafts.Shaft.class,
                PrintedBeamShafts.Shaft.class, float.class, ribbon);
        resolve.setAccessible(true);
        assertTrue((boolean) resolve.invoke(null, ink, WIDTH, HEIGHT, bar, first, last, (float) GAP, observed));
        for (int x = 74; x <= 111; x++) {
            int y = Math.round(98f + (x - 74) * 12f / 37);
            fill(ink, x, x, y - 3, y + 3, 0);
        }
        assertFalse((boolean) resolve.invoke(null, ink, WIDTH, HEIGHT, bar, first, last, (float) GAP, observed));
    }

    private static boolean hasStaffSecondary(boolean secondBeam, boolean erased)
            throws ReflectiveOperationException {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = 72; y <= 136; y += GAP) fill(ink, 20, 200, y - 1, y + 2, 0);
        fill(ink, 60, 110, 101, 108, 0);
        if (secondBeam) fill(ink, 60, 110, 86, 91, 0);
        if (erased) fill(ink, 77, 94, 101, 110, 255);
        var first = new PrintedBeamShafts.Shaft(59, 61, 44, 108, 1, List.of());
        var last = new PrintedBeamShafts.Shaft(109, 111, 44, 108, 1, List.of());
        var staff =
                StaffPitchTrack.fromVerifiedSamples(
                        List.of(new float[] {40, 136, GAP}, new float[] {130, 136, GAP}));
        var method =
                WrappedEighthTriplets.class.getDeclaredMethod(
                        "secondaryRibbonIsStaffRule",
                        byte[].class,
                        int.class,
                        int.class,
                        PrintedBeamShafts.Shaft.class,
                        PrintedBeamShafts.Shaft.class,
                        float.class,
                        StaffPitchTrack.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, ink, WIDTH, HEIGHT, first, last, (float) GAP, staff);
    }

    private static void fill(byte[] ink, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
