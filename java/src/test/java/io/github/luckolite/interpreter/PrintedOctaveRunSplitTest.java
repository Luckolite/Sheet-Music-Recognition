// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class PrintedOctaveRunSplitTest {
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 420;
    private static final int STAFF_TOP = 104;
    private static final int STAFF_BOTTOM = 168;
    private static final int CUT = 315;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final List<MeasureRegion> raw = List.of(bar(45, 470), bar(490, 930));
    private final List<MeasureRegion> fitted =
            List.of(bar(45, CUT - 4), bar(CUT + 4, 470), raw.get(1));
    private final List<ScoreNoteEvent> notes = new ArrayList<>();

    @Test
    public void completeOctaveRunsRejectAnUnprintedDivider() {
        draw();
        assertEquals(raw, reconcile());
    }

    @Test
    public void printedDividerOrUnprovedRailsKeepProposedMeasureBoundaries() {
        draw();
        paint(CUT - 1, CUT + 1, STAFF_TOP, STAFF_BOTTOM, 0);
        assertEquals(fitted, reconcile());

        draw();
        for (int group = 0; group < 2; group++) {
            int x = 75 + 4 * group * 32;
            paint(x + 15, x + 78, 80, 84, 255);
        }
        assertEquals(fitted, reconcile());
    }


    @Test
    public void printedInlineThreeFourCanProveOneClosedRunWithoutAPeer() throws Exception {
        assertEquals(raw, inlineRun('3', false));
        assertEquals(inlineFitted(), inlineRun('2', false));
        assertEquals(inlineFitted(), inlineRun('3', true));
    }

    private List<MeasureRegion> inlineRun(char numerator, boolean printedDivider) throws Exception {
        notes.clear();
        Arrays.fill(gray, (byte) 255);
        for (int y = STAFF_TOP; y <= STAFF_BOTTOM; y += 16) paint(32, 943, y, y, 0);
        drawRun(1, 550);

        byte[] signature = InlineTripleMeterProofTest.signature(numerator, '4', true);
        byte[] labels = new byte[gray.length];
        for (int y = STAFF_TOP; y <= STAFF_BOTTOM; y += 16)
            Arrays.fill(labels, y * WIDTH + 32, y * WIDTH + 944, (byte) 4);
        for (int y = 51; y <= 121; y++) {
            for (int x = 96; x <= 162; x++) {
                int at = (y + 50) * WIDTH + x + 369;
                gray[at] = signature[y * 320 + x];
                if (x >= 123 && x < 157 && (y - 54) % 16 != 0 && (gray[at] & 255) < 150)
                    labels[at] = 3;
            }
        }
        if (printedDivider) paint(670, 672, STAFF_TOP, STAFF_BOTTOM, 0);

        return PrintedMeasureRhythmGuard.preserveCompleteRuns(
                raw, inlineFitted(), notes, List.of(), labels, gray, WIDTH, HEIGHT);
    }

    private List<MeasureRegion> inlineFitted() {
        return List.of(raw.get(0), bar(490, 667), bar(675, 930));
    }


    @Test
    public void splitThroughAnOwnedOctaveCannotDivideTwoPrintedFourBeatBars() {
        assertEquals(raw, fourBeatPair(243, true, true));
    }

    @Test
    public void requiresAnOwnedOctaveAtTheCutAndASeparatedCompletePeer() {
        assertEquals(fourBeatFitted(255), fourBeatPair(255, true, true));
        assertEquals(fourBeatFitted(243), fourBeatPair(243, false, true));
        assertEquals(fourBeatFitted(243), fourBeatPair(243, true, false));
    }

    private List<MeasureRegion> fourBeatPair(int cut, boolean betweenBars, boolean peerComplete) {
        notes.clear();
        Arrays.fill(gray, (byte) 255);
        for (int y = STAFF_TOP; y <= STAFF_BOTTOM; y += 16) paint(32, 943, y, y, 0);
        if (betweenBars) paint(479, 481, STAFF_TOP, STAFF_BOTTOM, 0);
        drawFourBeatRun(0, 75);
        if (peerComplete) drawFourBeatRun(1, 520);
        return PrintedMeasureRhythmGuard.preserveCompleteRuns(
                raw, fourBeatFitted(cut), notes, gray, WIDTH, HEIGHT);
    }

    private List<MeasureRegion> fourBeatFitted(int cut) {
        return List.of(bar(45, cut - 4), bar(cut + 4, 470), raw.get(1));
    }

    private void drawFourBeatRun(int bar, int start) {
        var region = raw.get(bar);
        for (int i = 0; i < 16; i++) {
            int x = start + i * 24;
            if (i % 4 == 0) {
                paint(x + 8, x + 82, 66, 70, 0);
                paint(x + 8, x + 82, 80, 84, 0);
            }
            paint(x + 8, x + 10, 68, 159, 0);
            paint(x - 5, x + 10, 99, 107, 0);
            paint(x - 5, x + 10, 155, 163, 0);
            float pos = (x / (float) WIDTH - region.left()) / (region.right() - region.left());
            notes.add(head(bar, pos, 8, 103, false));
            notes.add(head(bar, pos, 1, 159, false));
        }
    }


    @Test
    public void clippedOctaveAndPrintedThreeFourOwnTheCompleteBar() throws Exception {
        assertEquals(inlineRaw(), throughStaffOctaves('3', 430, true));
    }

    @Test
    public void clippedRunNeedsItsOwnSignatureShaftAndClosingBarline() throws Exception {
        assertEquals(throughStaffFitted(430), throughStaffOctaves('2', 430, true));
        assertEquals(throughStaffFitted(445), throughStaffOctaves('3', 445, true));
        assertEquals(throughStaffFitted(430), throughStaffOctaves('3', 430, false));
    }

    private List<MeasureRegion> throughStaffOctaves(char numerator, int cut, boolean closed)
            throws Exception {
        notes.clear();
        Arrays.fill(gray, (byte) 255);
        for (int y = STAFF_TOP; y <= STAFF_BOTTOM; y += 16) paint(32, 943, y, y, 0);
        byte[] signature = InlineTripleMeterProofTest.signature(numerator, '4', true);
        byte[] labels = new byte[gray.length];
        for (int y = STAFF_TOP; y <= STAFF_BOTTOM; y += 16)
            Arrays.fill(labels, y * WIDTH + 32, y * WIDTH + 944, (byte) 4);
        for (int y = 51; y <= 121; y++) {
            for (int x = 96; x <= 162; x++) {
                int at = (y + 50) * WIDTH + x + 49;
                gray[at] = signature[y * 320 + x];
                if (x >= 123 && x < 157 && (y - 54) % 16 != 0 && (gray[at] & 255) < 150)
                    labels[at] = 3;
            }
        }
        if (closed) paint(649, 651, STAFF_TOP, STAFF_BOTTOM, 0);
        // A continuous twice-beamed run has no two independent four-attack closures.
        paint(258, 590, 66, 70, 0);
        paint(258, 590, 80, 84, 0);
        var bars = inlineRaw();
        var region = bars.get(1);
        for (int i = 0; i < 12; i++) {
            int x = 250 + i * 30;
            paint(x + 8, x + 10, 68, 159, 0);
            paint(x - 5, x + 10, 99, 107, 0);
            paint(x - 5, x + 10, 155, 163, 0);
            float pos = (x / (float) WIDTH - region.left()) / (region.right() - region.left());
            notes.add(head(1, pos, 8, 103, false));
            notes.add(head(1, pos, 1, 159, false));
        }
        return PrintedMeasureRhythmGuard.preserveCompleteRuns(
                bars, throughStaffFitted(cut), notes, List.of(), labels, gray, WIDTH, HEIGHT);
    }

    private List<MeasureRegion> inlineRaw() {
        return List.of(bar(45, 150), bar(170, 640), bar(660, 930));
    }

    private List<MeasureRegion> throughStaffFitted(int cut) {
        var measures = inlineRaw();
        return List.of(measures.get(0), bar(170, cut - 4), bar(cut + 4, 640), measures.get(2));
    }

    private List<MeasureRegion> reconcile() {
        return PrintedMeasureRhythmGuard.preserveCompleteRuns(
                raw, fitted, notes, gray, WIDTH, HEIGHT);
    }

    private void draw() {
        notes.clear();
        Arrays.fill(gray, (byte) 255);
        for (int y = STAFF_TOP; y <= STAFF_BOTTOM; y += 16) paint(32, 943, y, y, 0);
        for (int i = 0; i < raw.size(); i++) drawRun(i, i == 0 ? 75 : 520);
    }

    private void drawRun(int index, int start) {
        var region = raw.get(index);
        for (int i = 0; i < 12; i++) {
            int x = start + i * 32;
            if (i % 4 == 0) {
                paint(x + 8, x + 106, 66, 70, 0);
                paint(x + 8, x + 106, 80, 84, 0);
            }
            paint(x + 8, x + 10, 68, 159, 0);
            paint(x - 5, x + 10, 99, 107, 0);
            paint(x - 5, x + 10, 155, 163, 0);
            float pos = (x / (float) WIDTH - region.left()) / (region.right() - region.left());
            notes.add(head(index, pos, 8, 103, i == 2));
            notes.add(head(index, pos, 1, 159, i == 2));
        }
    }

    private static ScoreNoteEvent head(int bar, float pos, int step, int y, boolean pendingTie) {
        return new ScoreNoteEvent(bar, pos, step, 0, 1, y / (float) HEIGHT,
                pendingTie, 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0,
                ScoreNoteEvent.CLEF_TREBLE, false, 0, false, 0, 0, 1, 1);
    }

    private static MeasureRegion bar(int x0, int x1) {
        return new MeasureRegion(x0 / (float) WIDTH, x1 / (float) WIDTH, 66f / HEIGHT, 201f / HEIGHT);
    }

    private void paint(int x0, int x1, int y0, int y1, int value) {
        for (int y = y0; y <= y1; y++)
            Arrays.fill(gray, y * WIDTH + x0, y * WIDTH + x1 + 1, (byte) value);
    }
}
