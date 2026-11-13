// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class SavedOpeningPulseTest {
    @Test
    public void unsetSavedTempoUsesThePrintedOpeningRate() {
        var opening = List.of(new ScoreTempoChange(0, 0, 130, 2),
                new ScoreTempoChange(8, 0, 70, 2));
        double rate = ScoreTempoSelection.playbackQuarterBpm(0, 100, opening);
        assertEquals(130, rate, 0);
        assertEquals(opening, ScoreTempoSelection.atOpeningTempo(rate, opening));
        assertEquals(100, ScoreTempoSelection.playbackQuarterBpm(100, 100, opening), 0);
    }

    @Test
    public void matchesPrintedEighthPulseWithoutScalingLaterTempo() {
        var marks = List.of(new ScoreTempoChange(0, 0, 60, .5),
                new ScoreTempoChange(4, 0, 78, .5));
        double bpm = ScoreTempoSelection.playbackQuarterBpm(120, 180, marks);
        assertEquals(60, bpm, 0);
        assertEquals(marks, ScoreTempoSelection.atOpeningTempo(bpm, marks));
    }

    @Test
    public void keepsExplicitFallbackWithoutAnExactOpeningMatch() {
        var opening = List.of(new ScoreTempoChange(0, 0, 60, .5));
        assertEquals(90, ScoreTempoSelection.playbackQuarterBpm(60, 90, opening), 0);
        var later = List.of(new ScoreTempoChange(1, 0, 60, .5));
        assertEquals(180, ScoreTempoSelection.playbackQuarterBpm(120, 180, later), 0);
    }
}
