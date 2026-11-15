// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original asymmetric eight-eighth/half-quarter-quarter engraving with a printed bar. */
public class PrintedVoiceMeasureCutTest {
    private static final int W = 500, H = 240;
    private final MeasureRegion region = new MeasureRegion(.04f, .96f, 60f / H, 170f / H);

    private byte[] page(boolean bar, int paper, boolean shortStem) {
        byte[] g = new byte[W * H];
        Arrays.fill(g, (byte) paper);
        for (int y = 80; y <= 144; y += 16) for (int x = 20; x < 480; x++) g[y * W + x] = 30;
        if (bar) for (int y = (shortStem ? 100 : 80); y <= 144; y++) g[y * W + 340] = 25;
        return g;
    }

    private List<ScoreNoteEvent> notes() {
        var a = new ArrayList<ScoreNoteEvent>();
        float[] x = {.05f, .12f, .2f, .28f, .36f, .44f, .52f, .6f, .76f, .85f, .93f};
        for (int i = 0; i < x.length; i++)
            a.add(
                    new ScoreNoteEvent(
                            0,
                            x[i],
                            i % 6,
                            0,
                            1,
                            .5f,
                            false,
                            0,
                            i < 8 ? 1 : 0,
                            2,
                            i == 8 ? 2 : i < 8 ? 0 : 1));
        return a;
    }

    private Float cut(byte[] g) {
        return PrintedVoiceMeasureCut.resolve(region, notes(), 0, g, W, H);
    }

    @Test
    public void asymmetricCompleteBarsUseThePrintedCut() {
        assertEquals(.68f, cut(page(true, 240, false)), .003f);
    }

    @Test
    public void darkPaperUsesInkContrast() {
        assertEquals(.68f, cut(page(true, 130, false)), .003f);
    }

    @Test
    public void completeDurationsWithoutPrintedCutCannotInventOne() {
        assertNull(cut(page(false, 240, false)));
    }

    @Test
    public void shortStemCannotProveASeparator() {
        assertNull(cut(page(true, 240, true)));
    }

    @Test
    public void fittedBoundaryMovesOnlyWithCompleteProof() {
        var proposed =
                List.of(
                        new MeasureRegion(.04f, .498f, 60f / H, 170f / H),
                        new MeasureRegion(.502f, .96f, 60f / H, 170f / H));
        var result =
                PrintedMeasureRhythmGuard.alignPrintedSeparators(
                        List.of(region), proposed, notes(), page(true, 130, false), W, H);
        assertEquals(.678f, result.get(0).right(), .003f);
    }

    @Test
    public void callerGrayRemainsUnchanged() {
        byte[] g = page(true, 130, false), a = g.clone();
        cut(g);
        assertArrayEquals(a, g);
    }

    @Test
    public void leaningPhotographBarStillProvesTheCut() {
        byte[] g = page(false, 130, false);
        for (int y = 80; y <= 144; y++) g[y * W + Math.round(340 + .06f * (y - 112))] = 25;
        assertEquals(.68f, cut(g), .003f);
    }

    private static final int WIDTH = 720;
    private static final int HEIGHT = 270;
    private static final int TOP = 101;
    private static final int BOTTOM = 165;
    private static final int CUT = 394;

    @Test
    public void onlyAnIndependentlyUnownedRuleSurvivesACompetingPrintedShaft() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += 16) paint(gray, 55, 650, y, y, (byte) 0);
        paint(gray, CUT, CUT + 2, TOP, BOTTOM, (byte) 0);
        drawShaft(gray, 300);
        drawShaft(gray, 490);

        var region = new MeasureRegion(55f / WIDTH, 650f / WIDTH, 67f / HEIGHT, 198f / HEIGHT);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int x : new int[] {150, 300, 345, 447, 490, 534}) {
            notes.add(
                    new ScoreNoteEvent(
                            0,
                            (x - 55f) / 595,
                            x == 300 || x == 490 ? 8 : 2,
                            0,
                            1,
                            (x == 300 || x == 490 ? 98f : 150f) / HEIGHT,
                            false,
                            0,
                            0,
                            ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                            x == 300 ? 2 : 1,
                            1,
                            0,
                            0,
                            ScoreNoteEvent.CLEF_TREBLE,
                            false,
                            0,
                            false,
                            0,
                            0,
                            1,
                            -1,
                            ScoreNoteEvent.Kind.PITCHED));
        }
        var cut = PrintedVoiceMeasureCut.resolve(region, notes, 0, gray, WIDTH, HEIGHT);
        assertNotNull(cut);
        assertEquals(CUT + 1f, cut * WIDTH, 1);
        byte[] rival = gray.clone();
        paint(rival, 375, 377, TOP, BOTTOM, (byte) 0);
        assertNull(PrintedVoiceMeasureCut.resolve(region, notes, 0, rival, WIDTH, HEIGHT));
        paint(gray, CUT, CUT + 2, TOP + 1, BOTTOM - 1, (byte) 255);
        assertNull(PrintedVoiceMeasureCut.resolve(region, notes, 0, gray, WIDTH, HEIGHT));
    }

    private static void drawShaft(byte[] gray, int head) {
        paint(gray, head - 11, head - 10, 98, 165, (byte) 0);
        paint(gray, head - 11, head - 2, 98, 101, (byte) 0);
        for (int y = 92; y <= 104; y++)
            for (int x = head - 8; x <= head + 9; x++)
                if (Math.pow((x - head) / 8d, 2) + Math.pow((y - 98) / 5d, 2) <= 1)
                    gray[y * WIDTH + x] = 0;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, byte ink) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, ink);
    }
}
