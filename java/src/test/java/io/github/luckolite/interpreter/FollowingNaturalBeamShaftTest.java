// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class FollowingNaturalBeamShaftTest {
    private static final int WIDTH = 240;
    private static final int HEIGHT = 176;
    private static final float GAP = 16;
    private static final int STAFF_BOTTOM = 132;
    private static final StaffPitchTrack STAFF =
            StaffPitchTrack.fromVerifiedSamples(
                    List.of(new float[] {12, STAFF_BOTTOM, GAP}, new float[] {215, STAFF_BOTTOM, GAP}));
    private static final PrintedBeamShafts.Shaft COMPETING_SHAFT =
            new PrintedBeamShafts.Shaft(80, 81, 107, 76, -1, List.of());

    @Test
    public void detachedNaturalCanOwnAnAdjacentOpposedShaft() {
        var gray = paper();
        var labels = new byte[gray.length];
        natural(gray, labels);
        assertTrue(proves(gray, labels, 108));
        assertFalse(proves(gray, labels, 130));
    }

    @Test
    public void erasingEitherIndependentWingOrItsSemanticOwnershipVetoesTheCorrection() {
        var gray = paper();
        var labels = new byte[gray.length];
        natural(gray, labels);
        assertFalse(proves(gray, new byte[gray.length], 108));
        for (int y = 75; y < 124; y++) Arrays.fill(gray, y * WIDTH + 88, y * WIDTH + 95, (byte) 255);
        assertFalse(proves(gray, labels, 108));
    }

    @Test
    public void actualParallelShaftsHaveNoNaturalConnectors() {
        var gray = paper();
        var labels = new byte[gray.length];
        glyph(gray, labels, 80, 81, 76, 108);
        glyph(gray, labels, 90, 91, 89, 120);
        assertFalse(proves(gray, labels, 108));
    }

    private static boolean proves(byte[] gray, byte[] labels, float followingX) {
        return WrappedEighthTriplets.followingNaturalOwnsShaft(
                gray, labels, WIDTH, HEIGHT, STAFF, COMPETING_SHAFT, 68, followingX, 98, GAP);
    }

    private static byte[] paper() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = STAFF_BOTTOM - (int) GAP * 4; y <= STAFF_BOTTOM; y += (int) GAP) {
            Arrays.fill(gray, y * WIDTH + 24, y * WIDTH + 192, (byte) 0);
        }
        return gray;
    }

    private static void natural(byte[] gray, byte[] labels) {
        glyph(gray, labels, 80, 81, 76, 107);
        glyph(gray, labels, 90, 91, 89, 120);
        glyph(gray, labels, 80, 91, 90, 92);
        glyph(gray, labels, 80, 91, 103, 106);
    }

    private static void glyph(byte[] gray, byte[] labels, int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
            Arrays.fill(labels, y * WIDTH + left, y * WIDTH + right + 1, OmrMeasurePostProcessor.CLEF_OR_KEY);
        }
    }
}
