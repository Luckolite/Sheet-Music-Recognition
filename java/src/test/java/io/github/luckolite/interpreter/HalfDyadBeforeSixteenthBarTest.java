// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class HalfDyadBeforeSixteenthBarTest {
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 250;
    private static final int STAFF_TOP = 90;
    private static final int STAFF_GAP = 14;
    private static final int STAFF_BOTTOM = STAFF_TOP + 4 * STAFF_GAP;
    private static final int OPENING = 220;
    private static final int CLOSING = 775;
    private static final int FIRST_INNER_RULE = 291;
    private static final int SECOND_INNER_RULE = 298;
    private static final List<MeasureRegion> MERGED = List.of(
            region(40, 213), region(227, 768), region(782, 958));

    @Test
    public void doubleRuleSeparatesHalfDyadFromSixteenths() {
        var gray = staff(true);
        var measures = split(gray);
        assertEquals(2, measures.size());
        assertEquals(2, measures.get(0).cadenceQuarterBeats());
        assertEquals(0, measures.get(1).cadenceQuarterBeats());
        assertTrue(measures.get(0).right() * WIDTH < FIRST_INNER_RULE);
        assertTrue(measures.get(1).left() * WIDTH > SECOND_INNER_RULE);
        var notes = writtenHeads(measures);
        assertTrue(qualifies(gray, measures, notes));
    }

    @Test
    public void missingSecondStrokeDoesNotSplitCompleteDyads() {
        assertTrue(split(staff(false)).isEmpty());
    }

    @Test
    public void incompleteRightDyadDoesNotQualify() {
        var gray = staff(true);
        var measures = split(gray);
        assertEquals(2, measures.size());
        var notes = writtenHeads(measures);
        notes.remove(notes.size() - 1);
        assertFalse(qualifies(gray, measures, notes));
    }

    @Test
    public void separateShaftDirectionsDoNotQualifyAsOneCadenceChord() {
        var gray = staff(true);
        var measures = split(gray);
        assertEquals(2, measures.size());
        var notes = writtenHeads(measures);
        notes.set(0, head(measures, 0, 250, 125, 3, 0, 2, 1));
        assertFalse(qualifies(gray, measures, notes));
    }

    private static boolean qualifies(byte[] gray, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes) {
        return HalfDyadBeforeSixteenthBar.qualifies(measures,
                new OmrScoreInterpreter.Analysis(notes, List.of()), gray, WIDTH, HEIGHT);
    }

    private static List<MeasureRegion> split(byte[] gray) {
        return HalfDyadBeforeSixteenthBar.split(MERGED.get(0), MERGED.get(1), MERGED.get(2),
                gray, WIDTH, HEIGHT);
    }

    private static List<ScoreNoteEvent> writtenHeads(List<MeasureRegion> measures) {
        var notes = new ArrayList<ScoreNoteEvent>();
        notes.add(head(measures, 0, 250, 125, 3, 0, 2, -1));
        notes.add(head(measures, 0, 251, 97, 7, 0, 2, -1));
        for (int attack = 0; attack < 16; attack++) {
            int x = 324 + 28 * attack;
            notes.add(head(measures, 1, x, 125, 3, 2, 0, -1));
            notes.add(head(measures, 1, x + 1, 97, 7, 2, 0, -1));
        }
        return notes;
    }

    private static ScoreNoteEvent head(List<MeasureRegion> measures, int measure, int x, int y,
            int step, int beams, float unbeamedDuration, int stem) {
        var region = measures.get(measure);
        float position = (x / (float) WIDTH - region.left()) / (region.right() - region.left());
        return new ScoreNoteEvent(measure, position, step, 0, 1, y / (float) HEIGHT,
                false, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, unbeamedDuration,
                1, 0, 0, 30, false, 0, false, 0, 0, 1, stem, ScoreNoteEvent.Kind.PITCHED);
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH,
                61f / HEIGHT, 176f / HEIGHT);
    }

    private static byte[] staff(boolean secondStroke) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = STAFF_TOP; y <= STAFF_BOTTOM; y += STAFF_GAP)
            Arrays.fill(gray, y * WIDTH + 20, y * WIDTH + 985, (byte) 0);
        stroke(gray, OPENING);
        stroke(gray, CLOSING);
        stroke(gray, FIRST_INNER_RULE);
        if (secondStroke) stroke(gray, SECOND_INNER_RULE);
        return gray;
    }

    private static void stroke(byte[] gray, int x) {
        for (int y = STAFF_TOP; y <= STAFF_BOTTOM; y++)
            Arrays.fill(gray, y * WIDTH + x - 1, y * WIDTH + x + 2, (byte) 0);
    }
}
