// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class SavedOpeningPulseTest {
    @Test
    public void roundedPrintedNumberUsesItsOwnPulseWhenCatalogMeterDiffers() {
        var printed = List.of(new ScoreTempoChange(0, 0, 161.5),
                new ScoreTempoChange(8, 0, 80));
        double bpm = ScoreTempoSelection.playbackQuarterBpm(162, 324, printed);
        assertEquals(161.5, bpm, 0);
        assertEquals(printed, ScoreTempoSelection.atOpeningTempo(bpm, printed));
        assertEquals(360, ScoreTempoSelection.playbackQuarterBpm(180, 360, printed), 0);
        assertEquals(162, ScoreTempoSelection.playbackQuarterBpm(162, 162, printed), 0);
        assertEquals(324, ScoreTempoSelection.playbackQuarterBpm(162, 324,
                List.of(printed.get(0), new ScoreTempoChange(0, 0, 110))), 0);
    }

    @Test
    public void alternateEditionOwnsItsOpeningWhenSavedPulseMatchesPrimary() {
        var primary = List.of(new ScoreTempoChange(0, 0, 84, 1.5));
        var alternate = List.of(
                new ScoreTempoChange(0, 0, 105, 1.5), new ScoreTempoChange(8, 0, 120));
        double bpm = ScoreTempoSelection.arrangementQuarterBpm(56, 84, alternate, primary);
        assertEquals(105, bpm, 0);
        assertEquals(alternate, ScoreTempoSelection.atOpeningTempo(bpm, alternate));
        assertEquals(105, ScoreTempoSelection.arrangementQuarterBpm(84, 84, alternate, primary), 0);
        assertEquals(90, ScoreTempoSelection.arrangementQuarterBpm(60, 90, alternate, primary), 0);
        assertEquals(84, ScoreTempoSelection.arrangementQuarterBpm(
                56, 84, alternate, List.of(primary.get(0), new ScoreTempoChange(0, 0, 60))), 0);
        assertEquals(84, ScoreTempoSelection.arrangementQuarterBpm(
                56, 84, List.of(alternate.get(1)), primary), 0);
    }

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
