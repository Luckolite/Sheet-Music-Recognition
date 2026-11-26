// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;
import java.util.Optional;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MeasureRepeatProjectionTest {
    private static ScorePlaybackDirection repeat(int measure) {
        String id = "measure-repeat:0:" + measure;
        var evidence = new ScoreExpressiveEvent.Evidence(id, 0, .4f, 0, 1, "%");
        return new ScorePlaybackDirection(measure, ScorePlaybackDirection.Kind.MEASURE_REPEAT,
                new ScorePlaybackDirection.Details(0,id,"","","",1,List.of(),Optional.empty(),
                        ScorePlaybackDirection.AfterJumpRepeats.DEFAULT,"%",List.of(evidence)));
    }

    @Test public void consecutiveSignsKeepDestinationTimeAndOriginalSoundOwners() {
        var meter = new ScoreMeterMap(4,List.of());
        var plan = ScoreNavigationPlan.create(5,List.of(repeat(1),repeat(2),repeat(3)),meter);
        assertEquals(List.of(0,1,2,3,4),plan.sourceMeasures());
        assertTrue(plan.traversal().performanceQualified());
        var cue = new ScoreNavigationNoteProjection.SourceNote("cue",0,1.5,2.5);
        var entrance = new ScoreNavigationNoteProjection.SourceNote("solo",1,16.5,17);
        var content = List.of(cue,entrance);
        var ordinary = ScoreNavigationNoteProjection.project(
                content,plan,meter,ScoreNavigationNoteProjection.EntryPolicy.REATTACK);
        var expanded = MeasureRepeatProjection.append(ordinary,content,plan,meter,plan.measureRepeats());
        assertEquals(List.of(1.5,5.5,9.5,13.5,16.5),expanded.notes().stream()
                .map(ScoreNavigationNoteProjection.PerformedNote::startBeat).toList());
        assertEquals(List.of(2.5,6.5,10.5,14.5,17.),expanded.notes().stream()
                .map(ScoreNavigationNoteProjection.PerformedNote::endBeat).toList());
        assertEquals(List.of(0,0,0,0,1),expanded.notes().stream()
                .map(ScoreNavigationNoteProjection.PerformedNote::sourceIndex).toList());
        assertEquals(20,expanded.performedBeats(),0);
    }

    @Test public void aDifferentBarSpanOrWrittenDestinationDoesNotAcquireCueNotes() {
        var meter = new ScoreMeterMap(4,List.of(new ScoreMeterChange(2,2,4)));
        var plan = ScoreNavigationPlan.create(4,List.of(repeat(1),repeat(2)),meter);
        var cue = new ScoreNavigationNoteProjection.SourceNote("cue",0,1.5,2.5);
        var existing = new ScoreNavigationNoteProjection.SourceNote("written",1,4.5,5);
        var content = List.of(cue,existing);
        var ordinary = ScoreNavigationNoteProjection.project(
                content,plan,meter,ScoreNavigationNoteProjection.EntryPolicy.REATTACK);
        assertEquals(ordinary,MeasureRepeatProjection.append(ordinary,content,plan,meter,plan.measureRepeats()));
    }

    @Test public void aWrittenDestinationBreaksTheRepeatChain() {
        var meter = new ScoreMeterMap(4,List.of());
        var plan = ScoreNavigationPlan.create(4,List.of(repeat(1),repeat(2)),meter);
        var cue = new ScoreNavigationNoteProjection.SourceNote("cue",0,1.5,2.5);
        var existing = new ScoreNavigationNoteProjection.SourceNote("written",1,4.5,5);
        var content = List.of(cue,existing);
        var ordinary = ScoreNavigationNoteProjection.project(
                content,plan,meter,ScoreNavigationNoteProjection.EntryPolicy.REATTACK);
        assertEquals(ordinary,MeasureRepeatProjection.append(ordinary,content,plan,meter,plan.measureRepeats()));
    }

    @Test public void aLegacyRepeatWithoutSourceEvidenceRemainsUnqualified() {
        var mark = new ScorePlaybackDirection(1,ScorePlaybackDirection.Kind.MEASURE_REPEAT);
        var plan = ScoreNavigationPlan.create(3,List.of(mark),new ScoreMeterMap(4,List.of()));
        assertTrue(plan.measureRepeats().isEmpty());
        assertEquals("UNSUPPORTED_MEASURE_REPEAT",plan.traversal().diagnostics().get(0).code());
    }
}
