// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class ScoreRhythmicFeelTest {
    @Test
    public void capturesTheOpeningAndBothPrintedSwitches() {
        var score = page();
        var words = List.of(word("Swing 8ths q = 102", .12f),
                word("Play straight", .38f), word("Back to swing", .65f),
                word("The Swing Eighths Story", .65f));
        var events = ScoreRhythmicFeel.capture(score, words,
                List.of(new PlayingTechniqueDetector.Staff(120, 168, 12, 0, 1)), 620, 340);
        assertEquals(List.of(0, 1, 2),
                events.stream().map(e -> e.start().orElseThrow().measureIndex()).toList());
        assertEquals(List.of(ScoreRhythmicFeel.Feel.SWING, ScoreRhythmicFeel.Feel.STRAIGHT,
                ScoreRhythmicFeel.Feel.SWING),
                events.stream().map(e -> ScoreRhythmicFeel.read(e).orElseThrow()).toList());
    }

    @Test
    public void realizesPairsAndProvedHalfBeatRestsWithoutChangingTuplets() {
        var words = List.of(word("Rock Swing", .12f), word("Play straight", .38f),
                word("Back to swing", .65f));
        var events = ScoreRhythmicFeel.capture(page(), words,
                List.of(new PlayingTechniqueDetector.Staff(120, 168, 12, 0, 1)), 620, 340);
        var written = List.of(note(0), note(0), note(1), note(1),
                note(2).withLeadingRest(.5f), note(2).withTupletRatio(3, 2), note(2));
        var sounds = List.of(sound(0, 0, .5), sound(1, .5, 1),
                sound(2, 4, 4.5), sound(3, 4.5, 5), sound(4, 8.5, 9),
                sound(5, 9, 9.5), sound(6, 9.5, 10));
        var result = ScoreRhythmicFeel.swingPairs(sounds, written, new ScoreMeterMap(4, List.of()), events);
        assertEquals(new ScoreRhythmicFeel.Interval(0, 2d / 3), result.get(0));
        assertEquals(new ScoreRhythmicFeel.Interval(2d / 3, 1), result.get(1));
        assertEquals(new ScoreRhythmicFeel.Interval(8 + 2d / 3, 9), result.get(4));
        for (int i : List.of(2, 3, 5, 6)) assertFalse(result.containsKey(i));
    }

    private static ScorePageInterpretation page() {
        return new ScorePageInterpretation(
                List.of(new MeasureRegion(.14f, .36f, .34f, .52f),
                        new MeasureRegion(.38f, .62f, .34f, .52f),
                        new MeasureRegion(.65f, .89f, .34f, .52f)),
                List.of(), 1, List.of(), List.of());
    }

    private static PlayingTechniqueDetector.Word word(String text, float left) {
        return new PlayingTechniqueDetector.Word(text, left, .29f, left + .15f, .33f);
    }

    private static ScoreNoteEvent note(int measure) {
        return new ScoreNoteEvent(measure, .2f, 2, 0, 1, .4f, false, 0, 1, 2);
    }

    private static ScoreNavigationNoteProjection.SourceNote sound(int index, double start, double end) {
        return new ScoreNavigationNoteProjection.SourceNote("sound-" + index, index, start, end);
    }
}
