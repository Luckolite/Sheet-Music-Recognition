// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.List;
import java.util.Optional;
import org.junit.Test;

public final class ParenthesizedSecondPassTest {
    private static final String SOURCE = "printed-second-pass-only:v1";
    private static final List<ScoreNoteEvent> NOTES = List.of(note(.1f), note(.3f), note(.8f));

    @Test
    public void onlyTheOwnedNotesDropFromTheFirstCompleteVisit() {
        var source = new ScoreNavigationNoteProjection.Result(
                List.of(hit(0, 0, "first"), hit(1, 1, "first"), hit(2, 2, "first"),
                        hit(0, 8, "second"), hit(1, 9, "second"), hit(2, 10, "second")), 16);
        var performed = ParenthesizedSecondPass.project(source, List.of(mark(SOURCE)), NOTES);
        assertEquals(16d, performed.performedBeats(), 0d);
        assertEquals(List.of(2d, 8d, 9d, 10d),
                performed.notes().stream().map(ScoreNavigationNoteProjection.PerformedNote::startBeat).toList());
        assertSame(source, ParenthesizedSecondPass.project(source, List.of(mark("other")), NOTES));
    }

    @Test
    public void unresolvedOrSplitVisitsKeepTheOriginalPerformance() {
        var one = new ScoreNavigationNoteProjection.Result(
                List.of(hit(0, 0, "first"), hit(1, 1, "first"), hit(2, 2, "first")), 4);
        var split = new ScoreNavigationNoteProjection.Result(
                List.of(hit(0, 0, "first"), hit(1, 1, "first"), hit(2, 2, "first"),
                        hit(0, 8, "second"), hit(1, 9, "other"), hit(2, 10, "second")), 16);
        assertSame(one, ParenthesizedSecondPass.project(one, List.of(mark(SOURCE)), NOTES));
        assertSame(split, ParenthesizedSecondPass.project(split, List.of(mark(SOURCE)), NOTES));
    }

    private static ScoreExpressiveEvent mark(String owner) {
        var positions = Float.floatToIntBits(.1f) + "," + Float.floatToIntBits(.3f);
        return new ScoreExpressiveEvent(SOURCE + ":0:0:0",
                ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                Optional.of(new ScoreAnchor(0, 0)), Optional.of(new ScoreAnchor(1, 0)),
                ScoreExpressiveEvent.Scope.UNRESOLVED, 0, 1, Optional.of(positions),
                ScoreExpressiveEvent.Strength.UNSPECIFIED, "",
                List.of(new ScoreExpressiveEvent.Evidence(owner, 0, .1f, 0, 1, "second time only")));
    }

    private static ScoreNoteEvent note(float position) {
        return new ScoreNoteEvent(0, position, 4, 0, 1, .5f, false, 0, 1,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0);
    }

    private static ScoreNavigationNoteProjection.PerformedNote hit(int index, int beat, String visit) {
        return new ScoreNavigationNoteProjection.PerformedNote(visit + ":" + index, "note:" + index, index,
                beat, beat + .5, index, index + .5, false, false, List.of(visit));
    }
}
