// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrintedFlatGlyphTest {
    private final int w = 160, h = 150;
    private final byte[] gray = new byte[w * h];

    public PrintedFlatGlyphTest() {
        Arrays.fill(gray, (byte) 255);
    }

    private void rect(int x, int y, int ww, int hh) {
        for (int yy = y; yy < y + hh; yy++)
            for (int xx = x; xx < x + ww; xx++) gray[yy * w + xx] = 0;
    }

    private void flat() {
        rect(60, 50, 3, 41);
        for (int y = 69; y <= 87; y++)
            for (int x = 61; x <= 74; x++) {
                double r = Math.pow((x - 63) / 11d, 2) + Math.pow((y - 78) / 9d, 2);
                if (r >= .4 && r <= 1) gray[y * w + x] = 0;
            }
    }

    private boolean read(int top) {
        return PrintedFlatGlyph.matches(gray, w, h, 60, top, 74, 90, 16);
    }

    @Test
    public void sourceFlatSurvivesStaffCrossings() {
        flat();
        for (int y : new int[] {54, 70, 86}) rect(20, y, 120, 2);
        assertTrue(read(50));
    }

    @Test
    public void truncatedSemanticBoxCanRecoverItsPrintedSpine() {
        flat();
        assertTrue(read(69));
    }

    @Test
    public void naturalKeepsItsLowerRightStem() {
        rect(60, 50, 3, 32);
        rect(71, 60, 3, 31);
        rect(60, 60, 14, 3);
        rect(60, 79, 14, 3);
        assertFalse(read(50));
    }

    @Test
    public void sharpKeepsBothUpperSpines() {
        rect(60, 50, 3, 41);
        rect(71, 50, 3, 41);
        rect(57, 62, 21, 3);
        rect(57, 77, 21, 3);
        assertFalse(read(50));
    }

    @Test
    public void simpleStemHasNoBowl() {
        rect(60, 50, 3, 41);
        assertFalse(read(50));
    }

    @Test
    public void dotWindowBelongsToTheFollowingFlatOnlyWhileConnectedToItsSpine() {
        flat();
        // The lower bowl crosses the same aperture as a rhythm dot.
        rect(60, 82, 9, 4);
        var core = new WrappedTripletDotInk.Center(67, 84);
        assertTrue(WrappedTripletDotInk.singleFlatOwns(gray, w, h, core, 94, 75, 16));
        assertFalse(WrappedTripletDotInk.singleFlatOwns(gray, w, h, core, 94, 90, 16));

        for (int y = 53; y < 80; y++) Arrays.fill(gray, y * w + 59, y * w + 64, (byte) 255);
        assertFalse(WrappedTripletDotInk.singleFlatOwns(gray, w, h, core, 94, 75, 16));
    }

    @Test
    public void adjacentLocalFlatsAreNotAKeyChange() {
        byte[] labels = new byte[400 * 240];
        for (int y = 80; y <= 120; y += 10) for (int x = 20; x < 380; x++) labels[y * 400 + x] = 4;
        RejectedKeyBoundaryTest.flat(labels, 90, 75);
        RejectedKeyBoundaryTest.flat(labels, 105, 75);
        RejectedKeyBoundaryTest.head(labels, 118, 110);
        var result =
                OmrScoreInterpreter.analyze(
                        labels,
                        RejectedKeyBoundaryTest.raw(labels),
                        400,
                        240,
                        RejectedKeyBoundaryTest.M);
        assertTrue(result.keyChanges().isEmpty());
    }

    @Test
    public void completeFlatOutcomeRereadsCallerAndKeepsItsPixels() {
        flat();
        byte[] before = gray.clone();
        assertTrue(read(50));
        assertTrue(read(50));
        assertArrayEquals(before, gray);
        Arrays.fill(gray, (byte) 255);
        byte[] white = gray.clone();
        assertFalse(read(50));
        assertFalse(read(50));
        assertArrayEquals(white, gray);
    }

    @Test
    public void aRepeatedHeadRetainsItsLiteralChromaticSign() {
        var bar = new MeasureRegion(20f / w, 124f / w, 8f / h, 139.2f / h);
        var original = List.of(repeatedHead(45, 2), repeatedHead(89, 0));
        var score = new ScorePageInterpretation(List.of(bar), original);
        flat();
        byte[] labels = new byte[w * h];
        for (int i = 0; i < labels.length; i++)
            if (gray[i] == 0) labels[i] = OmrMeasurePostProcessor.CLEF_OR_KEY;
        var recorded = new ArrayList<SourceLocalAccidental.ProvedMark>();
        var result = PrintedAccidentalSpineRecovery.reconcileRepeatedMonophonicSigns(
                score, labels, gray, gray.clone(), w, h, recorded::add);
        assertEquals(List.of(original.get(0), original.get(1).withWrittenAccidental(-1)), result.notes());
        assertEquals(1, recorded.size());
        assertSame(score, PrintedAccidentalSpineRecovery.reconcileRepeatedMonophonicSigns(
                score, new byte[w * h], gray, gray.clone(), w, h, mark -> fail("unowned flat")));

        byte[] blank = new byte[w * h];
        Arrays.fill(blank, (byte) 255);
        assertSame(score, PrintedAccidentalSpineRecovery.reconcileRepeatedMonophonicSigns(
                score, labels, gray, blank, w, h, mark -> fail("unproved sign")));

        Arrays.fill(gray, (byte) 255);
        rect(62, 50, 3, 45);
        rect(70, 50, 3, 45);
        rect(59, 67, 16, 2);
        rect(59, 83, 16, 2);
        result = PrintedAccidentalSpineRecovery.reconcileRepeatedMonophonicSigns(
                score, new byte[w * h], gray, gray.clone(), w, h, mark -> {});
        assertEquals(List.of(original.get(0), original.get(1).withWrittenAccidental(1)), result.notes());
    }

    private ScoreNoteEvent repeatedHead(int x, int accidental) {
        return new ScoreNoteEvent(0, (x - 20f) / 104f, 3, 0, 1, 76f / h,
                false, 0, 2, accidental, 0)
                .withStemDirection(-1).withClef(ScoreNoteEvent.CLEF_TREBLE);
    }

    @Test
    public void edgeFlatSkipsUnavailableRuleProbeAndKeepsGuardErrors() {
        flat();
        byte[] edge = new byte[w * h];
        Arrays.fill(edge, (byte) 255);
        for (int y = 0; y < h; y++)
            for (int x = 60; x <= 74; x++) edge[y * w + x - 60] = gray[y * w + x];
        byte[] before = edge.clone();
        assertTrue(PrintedFlatGlyph.matches(edge, w, h, 0, 50, 14, 90, 16));
        assertArrayEquals(before, edge);
        assertFalse(PrintedFlatGlyph.matches(null, w, h, 0, 50, 14, 90, 16));
        byte[] shortRaster = {(byte) 231};
        try {
            PrintedFlatGlyph.matches(shortRaster, w, h, 0, 50, 14, 90, 16);
            fail("the original first source-ink read must reject a short raster");
        } catch (ArrayIndexOutOfBoundsException expected) {
            assertEquals((byte) 231, shortRaster[0]);
        }
        Arrays.fill(edge, (byte) 255);
        before = edge.clone();
        for (float gap :
                new float[] {-0f, 0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertFalse(PrintedFlatGlyph.matches(edge, w, h, 0, 50, 14, 90, gap));
        assertFalse(PrintedFlatGlyph.matches(shortRaster, w, h, 0, 50, -1, 90, 16));
        assertArrayEquals(before, edge);
    }
}
