// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class PrintedPageEvidenceTest {
    private static final int WIDTH = 600, HEIGHT = 300;
    private static final ScorePageInterpretation FALSE_MEASURE =
            new ScorePageInterpretation(List.of(new MeasureRegion(.2f, .6f, .2f, .55f)), List.of());

    private static byte[] paper() {
        byte[] pixels = new byte[WIDTH * HEIGHT];
        Arrays.fill(pixels, (byte) 255);
        return pixels;
    }

    @Test
    public void illustratedCoverDoesNotBecomeAnEditableMeasure() {
        byte[] gray = paper();
        for (int y = 20; y < 70; y++) for (int x = 130; x < 390; x++) gray[y * WIDTH + x] = 0;
        assertEquals(
                0,
                PrintedPageEvidence.rejectStafflessPage(FALSE_MEASURE, gray, gray, WIDTH, HEIGHT, false)
                        .measures()
                        .size());
    }

    @Test
    public void printedEmptyBarAndTabRowStayAvailable() {
        byte[] gray = paper();
        for (int line = 0; line < 5; line++)
            for (int x = 60; x < 540; x++) gray[(110 + 15 * line) * WIDTH + x] = 0;
        assertSame(
                FALSE_MEASURE,
                PrintedPageEvidence.rejectStafflessPage(FALSE_MEASURE, gray, gray, WIDTH, HEIGHT, false));
        assertSame(
                FALSE_MEASURE,
                PrintedPageEvidence.rejectStafflessPage(
                        FALSE_MEASURE, paper(), paper(), WIDTH, HEIGHT, true));
    }

    @Test
    public void letterboxedArtworkWithInventedNotesIsEmpty() {
        var score = scoreWithHead();
        var source = letterboxedScene();
        var result = PrintedPageEvidence.rejectStafflessPage(
                score, paper(), source, WIDTH, HEIGHT, false);
        assertEquals(0, result.measures().size());
        assertEquals(0, result.notes().size());
    }

    @Test
    public void sourceStaffAndTabKeepTheirNotes() {
        var score = scoreWithHead();
        var source = letterboxedScene();
        assertSame(score, PrintedPageEvidence.rejectStafflessPage(
                score, paper(), source, WIDTH, HEIGHT, true));
        Arrays.fill(source, 96 * WIDTH, 173 * WIDTH, (byte) 190);
        for (int line = 0; line < 5; line++) {
            int row = (105 + 14 * line) * WIDTH;
            Arrays.fill(source, row + 60, row + 540, (byte) 0);
        }
        assertSame(score, PrintedPageEvidence.rejectStafflessPage(
                score, paper(), source, WIDTH, HEIGHT, false));
    }

    @Test
    public void populatedScoreWithoutLetterboxUsesExistingAdmission() {
        var score = scoreWithHead();
        assertSame(score, PrintedPageEvidence.rejectStafflessPage(
                score, paper(), paper(), WIDTH, HEIGHT, false));
    }

    @Test
    public void solidTintIsInsufficientEvidenceOfArtwork() {
        var score = scoreWithHead();
        byte[] source = new byte[WIDTH * HEIGHT];
        Arrays.fill(source, 45 * WIDTH, (HEIGHT - 45) * WIDTH, (byte) 130);
        assertSame(score, PrintedPageEvidence.rejectStafflessPage(
                score, paper(), source, WIDTH, HEIGHT, false));
    }

    private static ScorePageInterpretation scoreWithHead() {
        return new ScorePageInterpretation(FALSE_MEASURE.measures(),
                List.of(new ScoreNoteEvent(0, .45f, 0, 0, 1, .35f, false, 0, 0, 2, 1)));
    }

    private static byte[] letterboxedScene() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        var noise = new java.util.Random(17);
        for (int y = 45; y < HEIGHT - 45; y++) {
            for (int x = 0; x < WIDTH; x++) {
                gray[y * WIDTH + x] = (byte) (92 + noise.nextInt(68));
            }
        }
        return gray;
    }
}
