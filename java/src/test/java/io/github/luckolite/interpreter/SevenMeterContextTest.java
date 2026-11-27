// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SevenMeterContextTest {
    static List<ScoreNoteEvent> notes(float rest) {
        var n = new ArrayList<ScoreNoteEvent>();
        n.add(new ScoreNoteEvent(0, .1f, 0, 0, 1, .4f, false, 0, 1, 2, 0, 1, rest));
        for (int i = 0; i < 7; i++)
            n.add(
                    new ScoreNoteEvent(0, .4f + i * .08f, i, 0, 1, .4f, false, 0, 2, 2, 0, 1)
                            .withTupletRatio(7, 4));
        return n;
    }

    static List<ScoreMeterChange> meter() {
        return List.of(new ScoreMeterChange(0, 3, 4));
    }

    static List<ScoreNoteEvent> run(List<ScoreNoteEvent> n) {
        return TripletRhythmDetector.contextualSevens(n, meter(), false);
    }

    @Test
    public void sevenInSixFillsProvedThreeBeatBar() {
        var n = notes(1);
        var r = run(n);
        assertEquals(n.get(0), r.get(0));
        for (int i = 1; i < 8; i++) assertEquals(n.get(i).withTupletRatio(7, 6), r.get(i));
    }

    @Test
    public void existingSevenInFourCompleteBarStays() {
        var n = notes(1.5f);
        assertEquals(n, run(n));
    }

    @Test
    public void missingMeterAbstains() {
        var n = notes(1);
        assertEquals(n, TripletRhythmDetector.contextualSevens(n, List.of(), false));
    }

    @Test
    public void conflictingMeterAbstains() {
        var n = notes(1);
        assertEquals(
                n,
                TripletRhythmDetector.contextualSevens(
                        n,
                        List.of(new ScoreMeterChange(0, 3, 4), new ScoreMeterChange(0, 4, 4)),
                        false));
    }

    @Test
    public void pickupIsNotPadded() {
        var n = notes(1);
        assertEquals(n, TripletRhythmDetector.contextualSevens(n, meter(), true));
    }

    @Test
    public void sixMembersCannotStandForSeven() {
        var n = new ArrayList<>(notes(1));
        n.remove(7);
        assertEquals(n, run(n));
    }

    @Test
    public void futureMeterCannotBeBorrowed() {
        var n = notes(1);
        assertEquals(
                n,
                TripletRhythmDetector.contextualSevens(
                        n, List.of(new ScoreMeterChange(1, 3, 4)), false));
    }

    @Test
    public void mixedBeamValuesAbstain() {
        var n = new ArrayList<>(notes(1));
        n.set(
                4,
                new ScoreNoteEvent(0, .64f, 3, 0, 1, .4f, false, 0, 3, 2, 0, 1)
                        .withTupletRatio(7, 4));
        assertEquals(n, run(n));
    }

    @Test
    public void overfullBarIsNotChanged() {
        var n = notes(2);
        assertEquals(n, run(n));
    }

    @Test
    public void explicitSevenInSixRemains() {
        var n = run(notes(1));
        assertEquals(n, run(n));
    }

    private static List<ScoreNoteEvent> sevenThirtySeconds(float secondRest) {
        var result = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 7; i++)
            result.add(new ScoreNoteEvent(0, .03f + i * .05f, i, 0, 1, .4f, false, 0, 3, 2, 0, 1)
                    .withTupletRatio(7, 4));
        result.add(new ScoreNoteEvent(0, .41f, 7, 0, 1, .4f, false, 0, 1, 2, 0, 1, .5f));
        result.add(new ScoreNoteEvent(0, .61f, 8, 0, 1, .4f, false, 0, 1, 2, 0, 1, secondRest));
        result.add(new ScoreNoteEvent(0, .81f, 9, 0, 1, .4f, false, 0, 1, 2, 0, 1));
        result.add(new ScoreNoteEvent(0, .91f, 10, 0, 1, .4f, false, 0, 1, 2, 0, 1));
        return result;
    }

    @Test
    public void threeBeamSevenFillsProvedQuarterBeat() {
        var source = sevenThirtySeconds(.5f);
        var resolved = TripletRhythmDetector.contextualSevens(
                source, List.of(new ScoreMeterChange(0, 4, 4)), false);
        for (int i = 0; i < 7; i++) {
            var n = resolved.get(i);
            assertEquals(source.get(i).withTupletRatio(7, 8), n);
            assertEquals(1.0 / 7, ScoreNoteTiming.writtenDurationBeats(n), 1e-8);
            assertEquals(i / 7.0, ScoreNoteTiming.beatInMeasure(n, resolved, 4), 1e-8);
        }
        assertEquals(source.subList(7, 11), resolved.subList(7, 11));
        assertEquals(1, ScoreNoteTiming.beatInMeasure(resolved.get(7), resolved, 4), 1e-8);
        assertEquals(3.5, ScoreNoteTiming.beatInMeasure(resolved.get(10), resolved, 4), 1e-8);
    }

    @Test
    public void threeBeamSevenInFourStaysWhenItsClockAlreadyFillsTheBar() {
        var source = sevenThirtySeconds(1);
        assertEquals(source, TripletRhythmDetector.contextualSevens(
                source, List.of(new ScoreMeterChange(0, 4, 4)), false));
    }
}
