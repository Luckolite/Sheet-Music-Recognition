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
