// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class BottomNoteSecondPassTest {
    private static final List<PlayingTechniqueDetector.Staff> STAFFS = List.of(
            new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 2),
            new PlayingTechniqueDetector.Staff(190, 254, 16, 1, 2));
    private static final PlayingTechniqueDetector.Word INSTRUCTION =
            new PlayingTechniqueDetector.Word("Play bottom note 2nd time only", .10f, .076f, .32f, .096f);
    private static final List<ScoreNoteEvent> NOTES = List.of(
            note(.10f, 5, 0), note(.10f, 3, 0), note(.26f, 0, 0), note(.54f, 3, 0),
            note(.54f, 5, 0), note(.10f, 3, 1), note(.54f, 3, 1));

    @Test
    public void leavesUpperHeadAndAccompanimentOnBothVisits() {
        var written = capture(page(NOTES), INSTRUCTION);
        assertEquals(1, written.expressiveEvents().size());
        assertEquals(ScoreExpressiveEvent.Scope.NOTE, written.expressiveEvents().get(0).scope());
        var original = performance(2);
        var projected = ParenthesizedSecondPass.project(original, written.expressiveEvents(), NOTES);
        assertEquals(List.of(0, 2, 4, 5, 6, 0, 1, 2, 3, 4, 5, 6),
                projected.notes().stream().map(ScoreNavigationNoteProjection.PerformedNote::sourceIndex).toList());
        assertEquals(original.performedBeats(), projected.performedBeats(), 0d);
    }

    @Test
    public void onlyAnExactHeadingAtTheOpeningDyadsOwnsNotes() {
        var normal = page(NOTES);
        assertSame(normal, capture(normal, new PlayingTechniqueDetector.Word(
                "Play top note 2nd time only", .10f, .076f, .32f, .096f)));
        assertSame(normal, capture(normal, new PlayingTechniqueDetector.Word(
                INSTRUCTION.text(), .25f, .076f, .40f, .096f)));
        var ambiguous = page(List.of(note(.10f, 2, 0), note(.10f, 3, 0), note(.10f, 5, 0),
                note(.54f, 3, 0), note(.54f, 5, 0)));
        assertSame(ambiguous, capture(ambiguous, INSTRUCTION));
    }

    @Test
    public void keepsEveryAttackWhenThereIsNoSecondVisit() {
        var written = capture(page(NOTES), INSTRUCTION);
        var original = performance(1);
        assertSame(original, ParenthesizedSecondPass.project(original, written.expressiveEvents(), NOTES));
    }

    private static ScorePageInterpretation capture(
            ScorePageInterpretation page, PlayingTechniqueDetector.Word word) {
        return ParenthesizedSecondPass.captureBottomNotes(page, List.of(word), STAFFS, 800, 1000, 0);
    }

    private static ScorePageInterpretation page(List<ScoreNoteEvent> notes) {
        return new ScorePageInterpretation(List.of(new MeasureRegion(.10f, .45f, .09f, .26f)), notes);
    }

    private static ScoreNoteEvent note(float x, int step, int staff) {
        return new ScoreNoteEvent(0, x, step, staff, 2, .12f + .1f * staff, false, 0, 1,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0).withStemDirection(1);
    }

    private static ScoreNavigationNoteProjection.Result performance(int visits) {
        var hits = new ArrayList<ScoreNavigationNoteProjection.PerformedNote>();
        for (int visit = 0; visit < visits; visit++) {
            for (int owner = 0; owner < NOTES.size(); owner++) {
                double onset = owner * .5;
                double performed = visit * 4 + onset;
                hits.add(new ScoreNavigationNoteProjection.PerformedNote(visit + ":" + owner,
                        "note:" + owner, owner, performed, performed + .5, onset, onset + .5,
                        false, false, List.of("visit:" + visit)));
            }
        }
        return new ScoreNavigationNoteProjection.Result(hits, visits * 4);
    }
}
