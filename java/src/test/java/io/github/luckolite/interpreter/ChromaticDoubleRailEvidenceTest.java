// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ChromaticDoubleRailEvidenceTest {
    private static final int WIDTH = 290, HEIGHT = 255;
    private static final float GAP = 16;

    @Test
    public void isolatedCurvedStrokeCannotClaimATripletBracket() {
        byte[] paper = blank();
        for (int x = 48; x <= 215; x++) {
            int y = 187 + Math.round((x - 130) * (x - 130) * .0025f);
            for (int row = y - 3; row <= y + 3; row++) paper[row * WIDTH + x] = 0;
        }
        var rows = new UnnumberedTripletInk.Rows(0, HEIGHT - 1);
        assertTrue(UnnumberedTripletInk.bracketBesideBeam(
                paper, WIDTH, HEIGHT, GAP, 64, 172, 117, 140, 150, rows, null));
        assertFalse(UnnumberedTripletInk.bracketBesideBeam(
                paper, WIDTH, HEIGHT, GAP, 64, 172, 117, 140, 150, rows, null, true));

        for (int y = 169; y <= 171; y++) {
            Arrays.fill(paper, y * WIDTH + 59, y * WIDTH + 107, (byte) 0);
            Arrays.fill(paper, y * WIDTH + 129, y * WIDTH + 178, (byte) 0);
        }
        for (int y = 171; y <= 181; y++) {
            paper[y * WIDTH + 64] = 0;
            paper[y * WIDTH + 172] = 0;
        }
        assertTrue(UnnumberedTripletInk.bracketBesideBeam(
                paper, WIDTH, HEIGHT, GAP, 64, 172, 117, 140, 150, rows, null, true));
    }

    @Test
    public void aDottedRibbonFringeCannotOwnABracketWithoutATick() {
        byte[] paper = blank();
        for (int x = 26; x < 224; x += 8)
            Arrays.fill(paper, 95 * WIDTH + x, 95 * WIDTH + x + 5, (byte) 0);
        for (int y = 96; y <= 97; y++) {
            Arrays.fill(paper, y * WIDTH + 59, y * WIDTH + 108, (byte) 0);
            Arrays.fill(paper, y * WIDTH + 129, y * WIDTH + 179, (byte) 0);
        }
        var rows = new UnnumberedTripletInk.Rows(0, HEIGHT - 1);
        assertTrue(UnnumberedTripletInk.bracketBesideBeam(
                paper, WIDTH, HEIGHT, GAP, 64, 172, 117, 140, 150, rows, null));
        assertFalse(UnnumberedTripletInk.bracketBesideBeam(
                paper, WIDTH, HEIGHT, GAP, 64, 172, 117, 140, 150, rows, null, true));

        for (int x : new int[] {64, 172}) stroke(paper, x, 97, 107);
        assertTrue(UnnumberedTripletInk.bracketBesideBeam(
                paper, WIDTH, HEIGHT, GAP, 64, 172, 117, 140, 150, rows, null, true));
    }

    @Test
    public void fittedDoubleRailDistinguishesStaffRuleAndThirdRibbon() {
        byte[] paper = blank();
        var left = new PrintedBeamShafts.Shaft(52, 54, 90, 129, 1, List.of());
        var right = new PrintedBeamShafts.Shaft(120, 122, 90, 133, 1, List.of());
        var staff = StaffPitchTrack.fromVerifiedSamples(List.of(
                new float[] {45, 103, GAP}, new float[] {132, 103, GAP}));
        for (int x = 53; x <= 121; x++) {
            int y = 127 + Math.round((x - 53) / 17f);
            stroke(paper, x, y - 3, y + 3);
            stroke(paper, x, y - 16, y - 10);
            stroke(paper, x, 102, 104);
        }
        assertTrue(WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                paper, WIDTH, HEIGHT, left, right, GAP, staff));

        byte[] missing = paper.clone();
        for (int x = 77; x < 97; x++) {
            for (int y = 112; y <= 121; y++) missing[y * WIDTH + x] = (byte) 255;
        }
        assertFalse(WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                missing, WIDTH, HEIGHT, left, right, GAP, staff));

        for (int x = 53; x <= 121; x++) {
            int y = 98 + Math.round((x - 53) / 17f);
            stroke(paper, x, y - 3, y + 3);
        }
        assertFalse(WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                paper, WIDTH, HEIGHT, left, right, GAP, staff));
    }

    private static byte[] blank() {
        byte[] result = new byte[WIDTH * HEIGHT];
        Arrays.fill(result, (byte) 255);
        return result;
    }

    private static void stroke(byte[] paper, int x, int first, int last) {
        for (int y = first; y <= last; y++) paper[y * WIDTH + x] = 0;
    }
}
