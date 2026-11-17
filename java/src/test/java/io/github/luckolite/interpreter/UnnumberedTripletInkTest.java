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

    @Test public void extendingStrokeCannotClaimAnIndependentTripletBracket() {
        byte[] line = clearBeam();
        for (int x = 63; x <= 180; x++)
            line[208 * WIDTH + x] = 0;
        var rows = new UnnumberedTripletInk.Rows(0, HEIGHT - 1);
        assertTrue(UnnumberedTripletInk.bracketBesideBeam(
                line, WIDTH, HEIGHT, GAP, X[0], X[2], 134, 171, 174, rows, null));
        assertFalse(UnnumberedTripletInk.bracketBesideBeam(
                line, WIDTH, HEIGHT, GAP, X[0], X[2], 134, 171, 174, rows, null, true));

        byte[] marked = line.clone();
        bracket(marked, 221);
        assertTrue(UnnumberedTripletInk.bracketBesideBeam(
                marked, WIDTH, HEIGHT, GAP, X[0], X[2], 134, 171, 174, rows, null, true));

        byte[] connectedTicks = line.clone();
        for (int y = 198; y < 208; y++)
            connectedTicks[y * WIDTH + 92] = connectedTicks[y * WIDTH + 148] = 0;
        assertTrue(UnnumberedTripletInk.bracketBesideBeam(
                connectedTicks, WIDTH, HEIGHT, GAP, X[0], X[2], 134, 171, 174, rows, null, true));
    }

    @Test public void extendedDottedStrokeDoesNotOwnTheTriplet() {
        byte[] line = clearBeam();
        for (int x = 52; x < 178; x += 8) Arrays.fill(line, 208 * WIDTH + x, 208 * WIDTH + x + 6, (byte) 0);
        var rows = new UnnumberedTripletInk.Rows(0, HEIGHT - 1);
        assertTrue(UnnumberedTripletInk.bracketBesideBeam(
                line, WIDTH, HEIGHT, GAP, X[0], X[2], 134, 171, 174, rows, null));
        assertFalse(UnnumberedTripletInk.bracketBesideBeam(
                line, WIDTH, HEIGHT, GAP, X[0], X[2], 134, 171, 174, rows, null, true));
        for (int y = 198; y < 208; y++) {
            line[y * WIDTH + 92] = 0;
            line[y * WIDTH + 148] = 0;
        }
        assertTrue(UnnumberedTripletInk.bracketBesideBeam(
                line, WIDTH, HEIGHT, GAP, X[0], X[2], 134, 171, 174, rows, null, true));
    }

    @Test public void matchedBowCaptionDoesNotHideAnIndependentBracket() {
        var paper = clearBeam();
        bracket(paper, 107);
        var rows = new UnnumberedTripletInk.Rows(0, HEIGHT - 1);
        var staff = StaffPitchTrack.fromVerifiedSamples(List.of(new float[] {36, 223, GAP},
                new float[] {222, 223, GAP}));
        var caption = new PlayingTechniqueDetector.Word("segue", 84f / WIDTH, 96f / HEIGHT,
                160f / WIDTH, 118f / HEIGHT);
        assertFalse(clearCaptionFixture(paper, rows, staff, List.of()));
        assertTrue(clearCaptionFixture(paper, rows, staff, List.of(caption)));
        bracket(paper, 208);
        assertFalse(clearCaptionFixture(paper, rows, staff, List.of(caption)));
    }

    @Test
    public void largeStaffBowCaptionKeepsTheOriginalInkThreshold() {
        int width = 500, height = 550;
        float gap = 25;
        byte[] paper = new byte[width * height];
        Arrays.fill(paper, (byte) 255);
        float[] x = {160, 205, 250}, y = {250, 250, 250};
        var shafts = List.of(new PrintedBeamShafts.Shaft(159, 161, 250, 306, 1, List.of()),
                new PrintedBeamShafts.Shaft(204, 206, 250, 306, 1, List.of()),
                new PrintedBeamShafts.Shaft(249, 251, 250, 306, 1, List.of()));
        for (int row = 250; row <= 306; row++)
            for (var shaft : shafts)
                Arrays.fill(paper, row * width + shaft.left(), row * width + shaft.right() + 1, (byte) 0);
        for (int row = 305; row <= 307; row++)
            Arrays.fill(paper, row * width + 159, row * width + 252, (byte) 0);
        for (int row = 325; row <= 334; row++) {
            for (int letter : new int[] {170, 222, 242})
                Arrays.fill(paper, row * width + letter, row * width + letter + 10, (byte) 165);
            Arrays.fill(paper, row * width + 195, row * width + 207, (byte) 0);
        }
        var staff = StaffPitchTrack.fromVerifiedSamples(List.of(new float[] {75, 470, gap},
                new float[] {345, 470, gap}));
        var rows = new UnnumberedTripletInk.Rows(0, height - 1);
        var caption = new PlayingTechniqueDetector.Word("segue", 100f / width, 314f / height,
                325f / width, 389f / height);
        assertFalse(UnnumberedTripletInk.clearWithTrackedStaff(paper, width, height,
                gap, x, y, shafts, rows, staff, List.of()));
        assertTrue(UnnumberedTripletInk.clearWithTrackedStaff(paper, width, height,
                gap, x, y, shafts, rows, staff, List.of(caption)));

        for (int pixel = 0; pixel < paper.length; pixel++)
            if ((paper[pixel] & 255) == 165) paper[pixel] = (byte) 166;
        assertFalse(UnnumberedTripletInk.clearWithTrackedStaff(paper, width, height,
                gap, x, y, shafts, rows, staff, List.of(caption)));
    }

    private static boolean clearCaptionFixture(byte[] paper, UnnumberedTripletInk.Rows rows,
            StaffPitchTrack staff, List<PlayingTechniqueDetector.Word> words) {
        return UnnumberedTripletInk.clearFingeringBands(paper, WIDTH, HEIGHT, GAP, X[0], X[2],
                134, 171, 170, 174, rows, staff, null, List.of(), words);
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

    @Test
    public void boldTwoNeedsBothItsInkAndTheIndependentlyOwnedWord() throws Exception {
        int width = 80, height = 80, left = 32, top = 24;
        byte[] image = new byte[width * height];
        java.util.Arrays.fill(image, (byte) 255);
        for (int y = top; y < top + 8; y++) {
            for (int x = left; x < left + 14; x++) {
                if (y < top + 3 || x < left + 5 || x > left + 8)
                    image[y * width + x] = 0;
            }
        }
        for (int y = top + 8; y < top + 14; y++)
            for (int x = left + Math.max(0, 4 - (y - top - 8)); x <= left + 11; x++)
                image[y * width + x] = 0;
        for (int y = top + 14; y <= top + 17; y++)
            java.util.Arrays.fill(image, y * width + left, y * width + left + 14, (byte) 0);
        var sources = RawNumeralComponents.capture(image, width, height, 25, 18, 53, 48, 165, () -> {}, () -> {});
        var glyph = sources.components().get(0);
        var word = new PlayingTechniqueDetector.Word("2", 24f / width, 17f / height, 53f / width, 47f / height);
        var proof = UnnumberedTripletInk.class.getDeclaredMethod("ownedBoldTwo", byte[].class, int.class,
                int.class, RawNumeralComponents.Component.class, PlayingTechniqueDetector.Word.class);
        proof.setAccessible(true);
        org.junit.Assert.assertEquals(true, proof.invoke(null, image, width, height, glyph, word));
        org.junit.Assert.assertEquals(false, proof.invoke(null, image, width, height, glyph, null));
        for (int y = top + 4; y < top + 8; y++) image[y * width + left + 7] = 0;
        org.junit.Assert.assertEquals(false, proof.invoke(null, image, width, height, glyph, word));
    }
}
