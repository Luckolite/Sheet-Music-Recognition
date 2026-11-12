// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original raster and semantic-mask drawings; no score pixels or coordinates. */
public class SymbolTouchHarmonicsTest {
    private static final int W = 240, H = 240, X = 100, Y = 160, G = 20;
    private final byte[] gray = new byte[W * H], labels = new byte[W * H];

    public SymbolTouchHarmonicsTest() {
        Arrays.fill(gray, (byte) 255);
    }

    private void draw(boolean diamond, int stemShade) {
        draw(diamond, stemShade, 30);
    }

    private void draw(boolean diamond, int stemShade, int touchOffset) {
        int touchY = Y - touchOffset;
        for (int y = touchY - 15; y <= touchY + 15; y++)
            for (int x = X - 15; x <= X + 15; x++) {
                double radius =
                        diamond
                                ? (Math.abs(x - X) + Math.abs(y - touchY)) / 12d
                                : Math.sqrt(
                                        Math.pow((x - X) / 12d, 2)
                                                + Math.pow((y - touchY) / 7d, 2));
                if (radius >= .65 && radius <= 1.1) {
                    gray[y * W + x] = 0;
                    labels[y * W + x] = OmrMeasurePostProcessor.SYMBOL;
                }
            }
        for (int y = Y - 7; y <= Y + 7; y++)
            for (int x = X - 11; x <= X + 11; x++)
                if (Math.pow((x - X) / 11d, 2) + Math.pow((y - Y) / 7d, 2) <= 1) {
                    gray[y * W + x] = 0;
                    labels[y * W + x] = OmrMeasurePostProcessor.NOTEHEAD;
                }
        for (int y = Y - 30; y <= Y + 32; y++)
            for (int x = X - 11; x <= X - 9; x++) gray[y * W + x] = (byte) stemShade;
        for (int rule : new int[] {Y - 48, Y - 40})
            for (int dy = 0; dy < 4; dy++)
                for (int x = 20; x < 220; x++) gray[(rule + dy) * W + x] = 0;
    }

    private ScoreNoteEvent stopped(int beams, float duration) {
        return new ScoreNoteEvent(
                0,
                X / (float) W,
                0,
                0,
                1,
                Y / (float) H,
                false,
                0,
                beams,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                duration,
                0,
                0,
                0,
                ScoreNoteEvent.CLEF_TREBLE);
    }

    private List<ScoreNoteEvent> apply(byte[] mask, List<ScoreNoteEvent> notes) {
        return ArtificialHarmonics.apply(
                mask,
                gray,
                W,
                H,
                List.of(new MeasureRegion(0, 1, 0, 1)),
                notes,
                List.of(new PlayingTechniqueDetector.Staff(80, 160, G, 0, 1)));
    }

    private void erase(int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            for (int x = X - 19; x <= X - 6; x++) gray[y * W + x] = (byte) 255;
    }

    @Test
    public void symbolClassTouchWithoutDecodedUpperShiftsStoppedNote() {
        draw(true, 0);
        assertFalse(ArtificialHarmonics.diamond(gray, W, H, X, Y - 30, G));
        var n = stopped(2, 0);
        assertEquals(List.of(n), apply(null, List.of(n)));
        var result = apply(labels, List.of(n));
        assertEquals(1, result.size());
        assertEquals(n.withOctaveShift(2), result.get(0));
    }

    @Test
    public void fadedConnectedStemRetainsHarmonicEvidence() {
        for (int shade : new int[] {175, 190, 205}) {
            Arrays.fill(gray, (byte) 255);
            Arrays.fill(labels, (byte) 0);
            draw(true, shade);
            var n = stopped(2, 0);
            assertEquals("shade=" + shade, n.withOctaveShift(2), apply(labels, List.of(n)).get(0));
        }
    }

    @Test
    public void shortFadedStemBreakIsToleratedWithSymbolEvidence() {
        draw(true, 205);
        erase(Y - 13, Y - 11);
        var n = stopped(2, 0);
        assertEquals(n.withOctaveShift(2), apply(labels, List.of(n)).get(0));
    }

    @Test
    public void ordinaryHollowFourthRemainsUnshiftedWithSymbolMask() {
        draw(false, 0);
        var n = stopped(2, 0);
        assertEquals(List.of(n), apply(labels, List.of(n)));
    }

    @Test
    public void modelEvidenceCannotReplaceDiamondShape() {
        draw(false, 0);
        Arrays.fill(labels, OmrMeasurePostProcessor.SYMBOL);
        var n = stopped(2, 0);
        assertEquals(List.of(n), apply(labels, List.of(n)));
    }

    @Test
    public void absentOrWrongModelEvidenceDoesNotRelaxStrictShape() {
        draw(true, 0);
        var n = stopped(2, 0);
        assertEquals(List.of(n), apply(new byte[W * H], List.of(n)));
        Arrays.fill(labels, OmrMeasurePostProcessor.NOTEHEAD);
        assertEquals(List.of(n), apply(labels, List.of(n)));
        assertEquals(List.of(n), apply(new byte[7], List.of(n)));
    }

    @Test
    public void isolatedOneBeamNoteDoesNotUseSymbolFallback() {
        draw(true, 0);
        var n = stopped(1, 0);
        assertEquals(List.of(n), apply(labels, List.of(n)));
    }

    @Test
    public void unbeamedQuarterUsesTheSharedPrintedShaft() {
        draw(true, 0);
        var n = stopped(0, ScoreNoteEvent.DURATION_QUARTER).withStemDirection(-1);
        assertEquals(List.of(n), apply(null, List.of(n)));
        assertEquals(List.of(n.withOctaveShift(2)), apply(labels, List.of(n)));
        assertEquals(List.of(n.withStemDirection(1)), apply(labels, List.of(n.withStemDirection(1))));
    }

    @Test
    public void detachedDiamondAndOrdinaryFourthCannotShiftAQuarterNote() {
        var n = stopped(0, ScoreNoteEvent.DURATION_QUARTER).withStemDirection(-1);
        draw(true, 0);
        erase(Y - 16, Y - 8);
        assertEquals(List.of(n), apply(labels, List.of(n)));
        Arrays.fill(gray, (byte) 255);
        Arrays.fill(labels, (byte) 0);
        draw(false, 0);
        assertEquals(List.of(n), apply(labels, List.of(n)));
    }

    @Test
    public void adjacentThirdTouchDoesNotBecomeAFourthTouch() {
        draw(true, 0, G);
        var n = stopped(0, ScoreNoteEvent.DURATION_QUARTER).withStemDirection(-1);
        assertEquals(List.of(n), apply(labels, List.of(n)));
    }

    @Test
    public void chromaticThirdUsesTheKeyWithoutChangingTheWrittenNote() {
        draw(true, 0, G);
        for (int yy = 80; yy <= 160; yy += G)
            for (int xx = 10; xx < W - 10; xx++) {
                gray[yy * W + xx] = 0;
                if (labels[yy * W + xx] == 0)
                    labels[yy * W + xx] = OmrMeasurePostProcessor.STAFF;
            }
        var stop = stopped(0, ScoreNoteEvent.DURATION_QUARTER).withStemDirection(-1);
        var partner = new ScoreNoteEvent(0, X / (float) W, 4, 0, 1, 120f / H, false,
                0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, ScoreNoteEvent.DURATION_QUARTER)
                .withStemDirection(-1);
        var score = new ScorePageInterpretation(
                List.of(new MeasureRegion(0, 1, .24f, .84f)), List.of(stop, partner),
                1, List.of(), List.of(), List.of());
        assertEquals(new ArtificialHarmonics.ThirdPitch(64, 68, 92),
                ArtificialHarmonics.resolveThirdPitches(labels, gray, W, H, score, 4).get(stop));
        assertTrue(ArtificialHarmonics.resolveThirdPitches(labels, gray, W, H, score, 0).isEmpty());
        assertEquals(stop, score.notes().get(0));
    }

    @Test
    public void hollowStoppedNoteDoesNotUseSymbolFallback() {
        draw(true, 0);
        var n = stopped(2, ScoreNoteEvent.DURATION_HALF);
        assertEquals(List.of(n), apply(labels, List.of(n)));
    }

    @Test
    public void detachedTouchAboveDownStemIsInsufficient() {
        draw(true, 0);
        erase(Y - 20, Y - 8);
        var n = stopped(2, 0);
        assertEquals(List.of(n), apply(labels, List.of(n)));
    }

    @Test
    public void interruptedLowerStemIsInsufficient() {
        draw(true, 0);
        erase(Y + 8, Y + 20);
        var n = stopped(2, 0);
        assertEquals(List.of(n), apply(labels, List.of(n)));
    }

    @Test
    public void paperShadeIsNotFadedStemInk() {
        draw(true, 235);
        var n = stopped(2, 0);
        assertEquals(List.of(n), apply(labels, List.of(n)));
    }

    @Test
    public void arraysAndRepeatedInterpretationRemainStable() {
        draw(true, 205);
        byte[] beforeGray = gray.clone(), beforeLabels = labels.clone();
        var n = stopped(2, 0);
        var first = apply(labels, List.of(n));
        assertEquals(first, apply(labels, first));
        assertArrayEquals(beforeGray, gray);
        assertArrayEquals(beforeLabels, labels);
    }
}
