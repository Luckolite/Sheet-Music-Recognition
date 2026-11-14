// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class SystemHalfDyadCadenceTest {
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 280;
    private static final int STAFF_TOP = 90;
    private static final int STAFF_GAP = 14;
    private static final int STAFF_BOTTOM = STAFF_TOP + 4 * STAFF_GAP;
    private static final int INNER_RULE = 706;
    private static final MeasureRegion MERGED = region(220, 780, 61, 176);
    private static final MeasureRegion INLINE = region(805, 958, 61, 176);

    @Test
    public void printedRulesAllowCompleteRunToSeparateFromCadence() {
        assertEquals(INNER_RULE, separator(INLINE, true, true, false));
        var notes = writtenHeads();
        var parts = split(notes);
        assertEquals(2, parts.size());
        assertEquals(0, parts.get(0).cadenceQuarterBeats());
        assertEquals(2, parts.get(1).cadenceQuarterBeats());
        assertTrue(parts.get(0).right() * WIDTH < INNER_RULE);
        assertTrue(parts.get(1).left() * WIDTH > INNER_RULE);
    }

    @Test
    public void requiresBothTheInnerRuleAndClosingDoubleRule() {
        assertEquals(-1, separator(INLINE, false, true, false));
        assertEquals(-1, separator(INLINE, true, false, false));
    }

    @Test
    public void rejectsShaftsThatExtendPastTheStaffAndOverlappingStaffRows() {
        assertEquals(-1, separator(INLINE, true, true, true));
        assertEquals(-1, separator(region(805, 958, 90, 205), true, true, false));
    }

    @Test
    public void requiresCompleteSixteenthDyadsAndOneSharedHalfDyad() {
        var notes = writtenHeads();
        notes.remove(0);
        assertTrue(split(notes).isEmpty());
        notes = writtenHeads();
        notes.set(33, head(742, 97, 7, 0, 2, 1));
        assertTrue(split(notes).isEmpty());
    }

    private static int separator(
            MeasureRegion next, boolean inner, boolean closingSecond, boolean shaft) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = STAFF_TOP; y <= STAFF_BOTTOM; y += STAFF_GAP) {
            Arrays.fill(gray, y * WIDTH + 20, y * WIDTH + 985, (byte) 0);
        }
        if (inner) stroke(gray, INNER_RULE, shaft ? STAFF_TOP - STAFF_GAP : STAFF_TOP);
        stroke(gray, 787, STAFF_TOP);
        if (closingSecond) stroke(gray, 794, STAFF_TOP);
        return SystemHalfDyadCadence.separator(MERGED, next, gray, WIDTH, HEIGHT);
    }

    private static List<MeasureRegion> split(List<ScoreNoteEvent> notes) {
        return SystemHalfDyadCadence.splitIfComplete(
                MERGED, INNER_RULE, new OmrScoreInterpreter.Analysis(notes, List.of()), WIDTH, HEIGHT);
    }

    private static List<ScoreNoteEvent> writtenHeads() {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int attack = 0; attack < 16; attack++) {
            int x = 240 + 28 * attack;
            notes.add(head(x, 125, 3, 2, 0, -1));
            notes.add(head(x + 1, 97, 7, 2, 0, -1));
        }
        notes.add(head(741, 125, 3, 0, 2, -1));
        notes.add(head(742, 97, 7, 0, 2, -1));
        return notes;
    }

    private static ScoreNoteEvent head(
            int x, int y, int step, int beams, float unbeamedDuration, int stem) {
        float position = (x / (float) WIDTH - MERGED.left()) / (MERGED.right() - MERGED.left());
        return new ScoreNoteEvent(0, position, step, 0, 1, y / (float) HEIGHT,
                false, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, unbeamedDuration,
                1, 0, 0, 30, false, 0, false, 0, 0, 1, stem, ScoreNoteEvent.Kind.PITCHED);
    }

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH,
                top / (float) HEIGHT, bottom / (float) HEIGHT);
    }

    private static void stroke(byte[] gray, int x, int top) {
        for (int y = top; y <= STAFF_BOTTOM; y++) {
            Arrays.fill(gray, y * WIDTH + x - 1, y * WIDTH + x + 2, (byte) 0);
        }
    }
}
