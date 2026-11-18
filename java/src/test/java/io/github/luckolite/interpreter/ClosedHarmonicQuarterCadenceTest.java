// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClosedHarmonicQuarterCadenceTest {
    @Test
    public void positionsFollowWrittenOrderOnlyInProvedThreeBeatMeasure() {
        var heads = new ArrayList<ScoreNoteEvent>();
        for (int i = 8; i >= 0; i--) {
            heads.add(new ScoreNoteEvent(0, (i + 1) / 10f, 1, 0, 1, .32f,
                    false, 0, i == 8 ? 0 : 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                    i == 8 ? 1 : 0, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE)
                    .withStemDirection(1).withOctaveShift(2));
        }
        var onsets = ClosedHarmonicQuarterCadence.onsets(score(3, heads));
        assertEquals(9, onsets.size());
        for (int index = 0; index < heads.size(); index++)
            assertEquals((8 - index) * .25, onsets.get(index), 0);
        assertTrue(ClosedHarmonicQuarterCadence.onsets(score(0, heads)).isEmpty());
        heads.set(2, heads.get(2).withTupletRatio(3, 2));
        assertTrue(ClosedHarmonicQuarterCadence.onsets(score(3, heads)).isEmpty());
    }

    private static ScorePageInterpretation score(int beats, List<ScoreNoteEvent> heads) {
        return new ScorePageInterpretation(List.of(new MeasureRegion(.1f, .9f, .2f, .5f, beats)),
                heads, 0, List.of(), List.of(), List.of(), List.of());
    }
}
