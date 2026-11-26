// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class HeldTremoloLowerVoiceTest {
    private static ScoreNoteEvent held(boolean tied) {
        return new ScoreNoteEvent(14, .218f, 8, 0, 1, .2f, tied,
                0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 4)
                .withArticulations(NoteOrnament.withTremolo(0, 3));
    }

    private static ScoreNoteEvent moving(float x, int pitch, float duration, int dots) {
        return new ScoreNoteEvent(14, x, pitch, 0, 1, .24f, false,
                dots, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, duration).withStemDirection(-1);
    }

    @Test
    public void writtenLowerVoicesFillBarFromTheBarline() {
        var upper = held(true);
        var half = moving(.184f, 1, 2, 0);
        var second = moving(.664f, 6, 2, 0);
        var halves = List.of(half, upper, second);
        assertEquals(0, ScoreNoteTiming.beatInMeasure(half, halves, 4), 0);
        assertEquals(2, ScoreNoteTiming.beatInMeasure(second, halves, 4), 0);
        var dotted = moving(.184f, 5, 2, 1);
        var quarter = moving(.809f, 3, 1, 0);
        var mixed = List.of(dotted, upper, quarter);
        assertEquals(0, ScoreNoteTiming.beatInMeasure(dotted, mixed, 4), 0);
        assertEquals(3, ScoreNoteTiming.beatInMeasure(quarter, mixed, 4), 0);
    }

    @Test
    public void retainsASeparatelyWrittenLeadingRest() {
        var first = moving(.184f, 1, 2, 0);
        var next = moving(.664f, 6, 2, 0);
        var withRest = first.withLeadingRest(.5f);
        assertTrue(ScoreNoteTiming.beatInMeasure(withRest, List.of(withRest, held(true), next), 4) >= .5);
    }
}
