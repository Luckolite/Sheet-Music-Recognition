// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class UnnumberedTripletInkTest {
    private static final int WIDTH = 256, HEIGHT = 304;
    private static final float GAP = 16;
    private static final float[] X = {92, 120, 148}, Y = {134, 134, 134};
    private static final List<PrintedBeamShafts.Shaft> SHAFTS = List.of(
            shaft(92), shaft(120), shaft(148));

    @Test public void clearAndNumberedBeamsAreDistinguished() {
        byte[] paper = clearBeam();
        assertTrue(clear(paper));
        paint(paper, 116, 183, ".#####.", "##...##", ".....##", "....##.",
                "...###.", "....##.", ".....##", ".....##", "##...##", ".#####.");
        assertFalse(clear(paper));
    }

    @Test public void bracketsOnEitherSidePreventAutomaticSuppression() {
        for (int y : new int[] {99, 208}) {
            byte[] paper = clearBeam();
            assertTrue(clear(paper));
            for (int x = 91; x <= 110; x++) paper[y * WIDTH + x] = 0;
            for (int x = 131; x <= 151; x++) paper[y * WIDTH + x] = 0;
            for (int dy = 1; dy <= 5; dy++) {
                int inward = y < 134 ? y + dy : y - dy;
                paper[inward * WIDTH + 91] = paper[inward * WIDTH + 151] = 0;
            }
            assertFalse(clear(paper));
        }
    }

    @Test public void uncertainInkAndMarksJoinedToRailAreRetained() {
        byte[] uncertain = clearBeam();
        for (int dy = 0; dy < 12; dy++) uncertain[(183 + dy) * WIDTH + 118 + dy / 4] = (byte) 190;
        assertFalse(clear(uncertain));
        byte[] joined = clearBeam();
        for (int dy = 0; dy <= 18; dy++) joined[(169 + dy) * WIDTH + 116] = 0;
        assertFalse(clear(joined));
    }

    @Test public void previousSystemStrokesDoNotClaimTheCurrentBracket() {
        var here = new MeasureRegion(.10f, .80f, .43f, .71f);
        var previous = new MeasureRegion(.08f, .85f, .26f, .34f);
        var rows = UnnumberedTripletInk.sourceRows(here, List.of(previous, here), HEIGHT);
        byte[] paper = clearBeam();
        bracket(paper, 99);
        assertFalse(clear(paper));
        assertTrue(UnnumberedTripletInk.clear(paper, WIDTH, HEIGHT, GAP, X, Y, SHAFTS, rows));
        bracket(paper, 124);
        assertFalse(UnnumberedTripletInk.clear(paper, WIDTH, HEIGHT, GAP, X, Y, SHAFTS, rows));
    }

    @Test public void trackedStaffIgnoresOnlyContinuousPrintedRules() {
        var paper = clearBeam();
        float slope = .06f;
        for (int x = 36; x <= 222; x++) {
            int lowest = Math.round(223 + (x - 120) * slope);
            for (int rail = 0; rail < 5; rail++)
                for (int dy = -1; dy <= 1; dy++)
                    paper[(lowest - rail * 16 + dy) * WIDTH + x] = 0;
        }
        var verified = StaffPitchTrack.fromVerifiedSamples(List.of(
                new float[] {36, 223 - 84 * slope, GAP},
                new float[] {222, 223 + 102 * slope, GAP}));
        var page = new UnnumberedTripletInk.Rows(0, HEIGHT - 1);
        assertFalse(clear(paper));
        assertTrue(UnnumberedTripletInk.clearWithTrackedStaff(
                paper, WIDTH, HEIGHT, GAP, X, Y, SHAFTS, page, verified));

        var marked = paper.clone();
        bracket(marked, 111);
        assertFalse(UnnumberedTripletInk.clearWithTrackedStaff(
                marked, WIDTH, HEIGHT, GAP, X, Y, SHAFTS, page, verified));

        var printedThree = paper.clone();
        paint(printedThree, 116, 179, ".#####.", "##...##", ".....##", "....##.",
                "...###.", "....##.", ".....##", ".....##", "##...##", ".#####.");
        assertFalse(UnnumberedTripletInk.clearWithTrackedStaff(
                printedThree, WIDTH, HEIGHT, GAP, X, Y, SHAFTS, page, verified));
    }

    @Test public void isolatedBracketCannotBecomeAStaffRuleByProjectionAlone() {
        var paper = clearBeam();
        bracket(paper, 207);
        var assumed = StaffPitchTrack.fromVerifiedSamples(List.of(
                new float[] {36, 207, GAP}, new float[] {220, 207, GAP}));
        var page = new UnnumberedTripletInk.Rows(0, HEIGHT - 1);
        assertFalse(UnnumberedTripletInk.clearWithTrackedStaff(
                paper, WIDTH, HEIGHT, GAP, X, Y, SHAFTS, page, assumed));
    }

    private static void bracket(byte[] paper, int y) {
        Arrays.fill(paper,y * WIDTH + 91,y * WIDTH + 111,(byte) 0);
        Arrays.fill(paper,y * WIDTH + 131,y * WIDTH + 152,(byte) 0);
    }

    private static void paint(byte[] paper, int left, int top, String... lines) {
        for (int dy = 0; dy < lines.length; dy++)
            for (int dx = 0; dx < lines[dy].length(); dx++)
                if (lines[dy].charAt(dx) == '#') paper[(top + dy) * WIDTH + left + dx] = 0;
    }

    private static boolean clear(byte[] paper) {
        return UnnumberedTripletInk.clear(paper, WIDTH, HEIGHT, GAP, X, Y, SHAFTS);
    }

    private static PrintedBeamShafts.Shaft shaft(int x) {
        return new PrintedBeamShafts.Shaft(x-1, x+1, 134, 170, 1, List.of());
    }

    private static byte[] clearBeam() {
        byte[] paper = new byte[WIDTH * HEIGHT];
        Arrays.fill(paper, (byte) 255);
        for (int y = 169; y <= 171; y++) Arrays.fill(paper, y*WIDTH+91, y*WIDTH+150, (byte) 0);
        for (int y = 134; y <= 170; y++)
            for (var shaft : SHAFTS)
                Arrays.fill(paper, y*WIDTH+shaft.left(), y*WIDTH+shaft.right()+1, (byte) 0);
        return paper;
    }
}
