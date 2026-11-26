// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CompoundCadenzaReturnTest {
    private static final List<PlayingTechniqueDetector.Word> RETURN =
            List.of(new PlayingTechniqueDetector.Word("a tempo", .37f, .25f, .45f, .28f));

    @Test
    public void sourceGroupsAndCaptionRestoreCompoundTime() {
        var score = score(true, true);
        var result = PrintedCadenceDurations.restoreCompoundAfterCadenza(score, RETURN);
        assertEquals(List.of(new ScoreMeterChange(0, 2, 4), new ScoreMeterChange(1, 12, 8)),
                result.meterChanges());
        assertEquals(score.notes(), result.notes());
        assertEquals(score.measures(), result.measures());
    }

    @Test
    public void captionAndBothWrittenRhythmChecksAreRequired() {
        var score = score(true, true);
        assertSame(score, PrintedCadenceDurations.restoreCompoundAfterCadenza(score, List.of()));
        var incomplete = score(false, true);
        assertSame(incomplete, PrintedCadenceDurations.restoreCompoundAfterCadenza(incomplete, RETURN));
        var shorter = score(true, false);
        assertSame(shorter, PrintedCadenceDurations.restoreCompoundAfterCadenza(shorter, RETURN));
    }

    private static ScorePageInterpretation score(boolean allGroups, boolean wholeCadence) {
        var measures = List.of(new MeasureRegion(.1f, .38f, .3f, .5f),
                new MeasureRegion(.4f, .7f, .3f, .5f), new MeasureRegion(.72f, .9f, .3f, .5f));
        var notes = new ArrayList<ScoreNoteEvent>();
        var groups = new ArrayList<ScoreBeamSource.Group>();
        var origins = new ArrayList<Optional<ScoreBeamSource.Anchor>>();
        for (int i = 0; i < 16; i++) {
            notes.add(note(0, .05f + .06f * i, 0, 3, 0, 0));
            origins.add(Optional.empty());
        }
        for (int staff = 0; staff < 2; staff++) {
            for (int group = 0; group < 4; group++) {
                var columns = new ArrayList<ScoreBeamSource.Column>();
                for (int column = 0; column < 3; column++) {
                    var anchor = new ScoreBeamSource.Anchor(0, notes.size());
                    notes.add(note(1, .04f + .08f * (3 * group + column), staff, 1, 0, 0));
                    origins.add(Optional.of(anchor));
                    columns.add(new ScoreBeamSource.Column(List.of(anchor)));
                }
                if (staff == 0 || group < 3 || allGroups)
                    groups.add(new ScoreBeamSource.Group(
                            "group-" + staff + "-" + group, columns, List.of(1, 1), List.of()));
            }
            for (int col = 0; col < 3; col++) {
                notes.add(note(2, .07f + col * .28f, staff, 0, 1, col == 2 && wholeCadence ? 2 : 1));
                origins.add(Optional.empty());
            }
        }
        return new ScorePageInterpretation(measures, notes, 1, List.of(), List.of(),
                List.of(new ScoreMeterChange(0, 2, 4)), List.of(), List.of(), List.of(), List.of(), List.of())
                .withBeamSource(new ScoreBeamSource(groups, List.of(), origins));
    }

    private static ScoreNoteEvent note(
            int bar, float x, int staff, int beams, int dots, float duration) {
        return new ScoreNoteEvent(bar, x, 1, staff, 2, .37f + staff * .08f,
                false, dots, beams, 0, duration);
    }
}
