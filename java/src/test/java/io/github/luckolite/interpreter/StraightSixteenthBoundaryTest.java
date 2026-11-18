// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class StraightSixteenthBoundaryTest {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 420;
    private static final List<MeasureRegion> PARTS =
            List.of(
                    new MeasureRegion(20f / WIDTH, 580f / WIDTH, 90f / HEIGHT, 254f / HEIGHT),
                    new MeasureRegion(600f / WIDTH, 1160f / WIDTH, 90f / HEIGHT, 254f / HEIGHT));

    @Test
    public void separatedCompleteRunsRetainTheirAttackCountWithOneUnresolvedHead() {
        var notes = straightDyads();
        assertTrue(proved(notes));
        notes.remove(32);
        notes.add(note(0, 3, -8, 3, 0));
        assertTrue(proved(notes));
    }

    @Test
    public void nearbyThirdChordMemberOrMissingAttackKeepsTheOriginalBoundary() {
        var notes = straightDyads();
        notes.add(note(0, 3, 9, 2, 1));
        assertFalse(proved(notes));
        notes = straightDyads();
        notes.subList(32, 34).clear();
        assertFalse(proved(notes));
    }

    private static ArrayList<ScoreNoteEvent> straightDyads() {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int bar = 0; bar < PARTS.size(); bar++) {
            for (int attack = 0; attack < 16; attack++) {
                notes.add(note(bar, attack, 4, 2, 1));
                notes.add(note(bar, attack, 6, 2, 1));
            }
        }
        return notes;
    }

    private static boolean proved(List<ScoreNoteEvent> notes) {
        return StraightDyadCadenceBoundaries.closedSixteenthPair(
                PARTS, notes, List.of(), WIDTH, HEIGHT);
    }

    private static ScoreNoteEvent note(int bar, int attack, int step, int beams, int stem) {
        return new ScoreNoteEvent(
                        bar,
                        (24 + 34 * attack) / 560f,
                        step,
                        0,
                        1,
                        .4f,
                        false,
                        0,
                        beams,
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                        0,
                        0,
                        0,
                        0,
                        ScoreNoteEvent.CLEF_TREBLE)
                .withStemDirection(stem);
    }
}
