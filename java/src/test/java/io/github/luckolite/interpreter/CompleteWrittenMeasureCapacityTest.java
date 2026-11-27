
// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Invented single-line notation for complete measures challenged by a count fit. */
public class CompleteWrittenMeasureCapacityTest {
    private static final int WIDTH = 1000, HEIGHT = 300;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final List<MeasureRegion> raw =
            List.of(bar(.1f, .3f), bar(.31f, .5f), bar(.51f, .7f), bar(.71f, .9f));
    private final List<MeasureRegion> fitted = List.of(
            bar(.1f, .198f), bar(.202f, .3f), raw.get(1), raw.get(2), raw.get(3));
    private final List<ScoreNoteEvent> notes = new ArrayList<>();
    private final List<ScoreRestEvent> rests = new ArrayList<>();

    public CompleteWrittenMeasureCapacityTest() {
        Arrays.fill(gray, (byte) 255);
        for (int y = 100; y <= 180; y += 20)
            Arrays.fill(gray, y * WIDTH + 90, y * WIDTH + 911, (byte) 0);
        for (int x : new int[] {305, 505, 705}) vertical(x, 100, 180);
        for (int m = 0; m < 4; m++) {
            for (float p : new float[] {.12f, .29f, .5f, .79f})
                notes.add(note(m, p, 1, 1));
        }
    }

    @Test
    public void aStemlessWholeNoteEstablishesWrittenCapacity() {
        notes.removeIf(n -> n.measureIndex() == 2);
        notes.add(note(2, .18f, 4, 0));
        assertEquals(raw, reconcile());
    }

    @Test
    public void aWholeRestDoesNotContradictTwoSequentialWitnesses() {
        notes.set(0, note(0, .12f, 1, -1));
        notes.set(3, note(0, .79f, 1, -1));
        notes.removeIf(n -> n.measureIndex() == 2);
        rests.add(ScoreRestEvent.fullMeasure(2, .5f, .5f, .02f, 0, 1));
        assertEquals(raw, reconcile());
    }

    @Test
    public void aWholeRestCannotReplaceASecondWrittenWitness() {
        notes.removeIf(n -> n.measureIndex() == 2 || n.measureIndex() == 3);
        rests.add(ScoreRestEvent.fullMeasure(2, .5f, .5f, .02f, 0, 1));
        rests.add(ScoreRestEvent.fullMeasure(3, .5f, .5f, .02f, 0, 1));
        assertEquals(fitted, reconcile());
    }

    @Test
    public void simultaneousOpposedDirectionsDoNotEstablishOneClock() {
        notes.add(note(0, .5f, 1, -1));
        assertEquals(fitted, reconcile());
    }

    @Test
    public void aShaftExtendingOutsideStaffCannotSplitItsHead() {
        vertical(200, 65, 180);
        Arrays.fill(gray, 78 * WIDTH + 193, 78 * WIDTH + 208, (byte) 0);
        assertEquals(raw, reconcile());
        notes.set(2, note(0, .58f, 1, 1));
        assertEquals(fitted, reconcile());
    }

    @Test
    public void strictPrintedSeparatorVetoStillApplies() {
        vertical(200, 100, 180);
        assertEquals(fitted, reconcile());
    }

    private static MeasureRegion bar(float left, float right) {
        return new MeasureRegion(left, right, .2f, .8f);
    }

    private static ScoreNoteEvent note(int bar, float position, float length, int direction) {
        return new ScoreNoteEvent(bar, position, 2, 0, 1, .43f, false, 0, 0, 2, length)
                .withStemDirection(direction);
    }

    private void vertical(int x, int top, int bottom) {
        for (int y = top; y <= bottom; y++) gray[y * WIDTH + x] = 0;
    }

    private List<MeasureRegion> reconcile() {
        return PrintedMeasureRhythmGuard.preservePartialWrittenBars(raw, fitted, notes, rests,
                gray, WIDTH, HEIGHT);
    }
}
