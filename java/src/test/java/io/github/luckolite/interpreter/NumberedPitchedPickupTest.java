// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NumberedPitchedPickupTest {
    static ScorePageInterpretation score(boolean numbered, boolean shortBar, boolean tied) {
        var notes =
                List.of(
                        new ScoreNoteEvent(0, .32f, 0, 0, 1, .25f, tied, 0, 1, 2, 0),
                        new ScoreNoteEvent(0, .73f, 1, 0, 1, .24f, false, 0, 1, 2, 0));
        return new ScorePageInterpretation(
                        List.of(
                                new MeasureRegion(.1f, shortBar ? .18f : .3f, .2f, .3f),
                                new MeasureRegion(.31f, .6f, .2f, .3f),
                                new MeasureRegion(.61f, .9f, .2f, .3f)),
                        notes,
                        0,
                        List.of(),
                        List.of(),
                        List.of(new ScoreMeterChange(0, 4, 4)),
                        List.of())
                .withOpeningPickup(numbered);
    }

    @Test
    public void numberedTwoEighthsProveOneBeat() {
        assertEquals(1, ScoreOpeningDuration.provedQuarterBeats(score(true, true, false), 4), 0);
    }

    @Test
    public void missingNumberingProofKeepsPositionGuard() {
        assertTrue(
                Double.isNaN(
                        ScoreOpeningDuration.provedQuarterBeats(score(false, true, false), 4)));
    }

    @Test
    public void fullWidthBarCannotShorten() {
        assertTrue(
                Double.isNaN(
                        ScoreOpeningDuration.provedQuarterBeats(score(true, false, false), 4)));
    }

    @Test
    public void incomingTieCannotShorten() {
        assertTrue(
                Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(score(true, true, true), 4)));
    }

    @Test
    public void legacyListApiDoesNotInferProof() {
        var s = score(true, true, false);
        assertTrue(
                Double.isNaN(
                        ScoreOpeningDuration.provedQuarterBeats(
                                s.notes(), s.rests(), s.measures(), 4, 0)));
    }

    @Test
    public void printedPickupMayIncludeSignatureSpaceWhenNeighborBarsConfirmIt() {
        var regions = List.of(
                new MeasureRegion(.07f, .27f, .2f, .4f),
                new MeasureRegion(.28f, .55f, .2f, .4f),
                new MeasureRegion(.56f, .89f, .2f, .4f));
        var notes = List.of(
                new ScoreNoteEvent(0, .28f, 3, 0, 1, .25f, false, 0, 1, 2, 0),
                new ScoreNoteEvent(0, .76f, 5, 0, 1, .25f, false, 0, 1, 2, 0),
                new ScoreNoteEvent(1, .22f, 6, 0, 1, .25f, false, 0, 1, 2, 0),
                new ScoreNoteEvent(2, .24f, 0, 0, 1, .25f, false, 0, 1, 2, 0));
        var score = new ScorePageInterpretation(regions, notes, 0,
                List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)), List.of());
        assertTrue(Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(score, 4)));
        assertEquals(1, ScoreOpeningDuration.provedQuarterBeats(score.withOpeningPickup(true), 4), 0);
        var uncorroborated = new ScorePageInterpretation(regions, notes.subList(0, 3), 0,
                List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)), List.of());
        assertTrue(Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(
                uncorroborated.withOpeningPickup(true), 4)));
    }

    @Test
    public void compactOpeningNeedsTwoCompleteSingleStaffNeighbors() {
        var bars = List.of(
                new MeasureRegion(.12f, .21f, .22f, .34f),
                new MeasureRegion(.22f, .51f, .22f, .34f),
                new MeasureRegion(.52f, .84f, .22f, .34f));
        var notes = new ArrayList<ScoreNoteEvent>();
        notes.add(new ScoreNoteEvent(0, .28f, 3, 0, 1, .25f, false, 0, 1, 2, 0));
        notes.add(new ScoreNoteEvent(0, .75f, 4, 0, 1, .25f, false, 0, 1, 2, 0));
        for (int bar = 1; bar < 3; bar++) {
            for (int i = 0; i < 4; i++) {
                notes.add(new ScoreNoteEvent(bar, .08f + .24f * i, i, 0, 1, .25f,
                        false, 0, 0, 2, 1f, 1));
            }
        }
        var score = new ScorePageInterpretation(bars, notes, 0, List.of(), List.of(),
                List.of(new ScoreMeterChange(0, 4, 4)), List.of());
        assertEquals(1, ScoreOpeningDuration.provedQuarterBeats(score, 4), 0);
        assertTrue(Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(
                notes, List.of(), bars, 4, 0)));
        var incomplete = new ScorePageInterpretation(bars, notes.subList(0, notes.size() - 1),
                0, List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)), List.of());
        assertTrue(Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(incomplete, 4)));
    }

    @Test
    public void oneShortPickupMayAlignAnAttackWithARestOnTheOtherStaff() {
        var bars = List.of(
                new MeasureRegion(.07f, .27f, .2f, .4f),
                new MeasureRegion(.28f, .55f, .2f, .4f),
                new MeasureRegion(.56f, .89f, .2f, .4f));
        var notes = List.of(
                new ScoreNoteEvent(0, .28f, 3, 0, 2, .25f, false, 0, 1, 2, 0),
                new ScoreNoteEvent(0, .76f, 5, 0, 2, .25f, false, 0, 1, 2, 0),
                new ScoreNoteEvent(1, .22f, 6, 0, 2, .25f, false, 0, 1, 2, 0),
                new ScoreNoteEvent(2, .24f, 0, 0, 2, .25f, false, 0, 1, 2, 0));
        var score = new ScorePageInterpretation(bars, notes, 0,
                List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)),
                List.of(new ScoreRestEvent(0, .30f, .35f, .02f, 1, 2, 1)));
        assertEquals(1, ScoreOpeningDuration.provedQuarterBeats(score, 4), 0);
        var outOfLine = new ScorePageInterpretation(bars, notes, 0,
                List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)),
                List.of(new ScoreRestEvent(0, .60f, .35f, .02f, 1, 2, 1)));
        assertTrue(Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(outOfLine, 4)));
        var conflictingSpan = new ScorePageInterpretation(bars, notes, 0,
                List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)),
                List.of(new ScoreRestEvent(0, .30f, .35f, .02f, 1, 2, 2)));
        assertTrue(Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(conflictingSpan, 4)));
    }

    @Test
    public void onlyBoundDocumentStartCanIgnoreUnpairedIncomingShoulder() {
        var bars = List.of(
                new MeasureRegion(.08f, .17f, .25f, .4f),
                new MeasureRegion(.18f, .48f, .25f, .4f),
                new MeasureRegion(.49f, .83f, .25f, .4f));
        var notes = List.of(new ScoreNoteEvent(0, .38f, 3, 0, 1, .3f,
                false, 0, 0, 2, 1f, 1).withBoundaryTies(2));
        var page = new ScorePageInterpretation(bars, notes, 0, List.of(), List.of(),
                List.of(new ScoreMeterChange(0, 3, 4)), List.of()).withOpeningPickup(true);
        assertTrue(Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(page, 3)));
        assertTrue(Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(
                withPhysicalPage(page, 1), 3)));
        assertEquals(1, ScoreOpeningDuration.provedQuarterBeats(withPhysicalPage(page, 0), 3), 0);
    }

    private static ScorePageInterpretation withPhysicalPage(ScorePageInterpretation page, int index) {
        var origin = new ScoreClockContextSource.Origin(
                "a".repeat(64), index, 2, 32, 32, "b".repeat(64), "c".repeat(64));
        return page.withClockContextSource(new ScoreClockContextSource(origin,
                page.notes().size(), page.measures().size(), page.rests().size(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()));
    }

    @Test
    public void slightlyOffsetHeadsShareOnePickupChordAboveEachLeadingRest() {
        var bars = List.of(
                new MeasureRegion(.1f, .2f, .2f, .4f),
                new MeasureRegion(.21f, .5f, .2f, .4f),
                new MeasureRegion(.51f, .82f, .2f, .4f));
        var notes = List.of(
                new ScoreNoteEvent(0, .750f, 2, 0, 2, .25f, false, 0, 1, 2, 0).withLeadingRest(.5f),
                new ScoreNoteEvent(0, .751f, 9, 0, 2, .25f, false, 0, 1, 2, 0).withLeadingRest(.5f),
                new ScoreNoteEvent(0, .749f, 0, 1, 2, .35f, false, 0, 1, 2, 0).withLeadingRest(.5f),
                new ScoreNoteEvent(0, .753f, 7, 1, 2, .35f, false, 0, 1, 2, 0).withLeadingRest(.5f));
        var rests = List.of(
                new ScoreRestEvent(0, .38f, .25f, .02f, 0, 2, .5),
                new ScoreRestEvent(0, .38f, .35f, .02f, 1, 2, .5));
        var score = new ScorePageInterpretation(bars, notes, 0, List.of(), List.of(),
                List.of(new ScoreMeterChange(0, 4, 4)), rests);
        assertEquals(1, ScoreOpeningDuration.provedQuarterBeats(score, 4), 0);
    }

    @Test
    public void verifiedDocumentOpeningMayContainExtraSignatureSpace() {
        var bars = List.of(
                new MeasureRegion(.07f, .22f, .22f, .34f),
                new MeasureRegion(.24f, .49f, .22f, .34f),
                new MeasureRegion(.51f, .77f, .22f, .34f));
        var notes = new ArrayList<ScoreNoteEvent>();
        notes.add(new ScoreNoteEvent(0, .23f, 3, 0, 1, .26f, false, 0, 0, 2, 1f, 1));
        notes.add(new ScoreNoteEvent(0, .70f, 5, 0, 1, .26f, false, 0, 0, 2, 1f, 1));
        for (int bar = 1; bar < 3; bar++) {
            for (int i = 0; i < 4; i++) {
                notes.add(new ScoreNoteEvent(bar, .09f + .24f * i, i, 0, 1, .26f,
                        false, 0, 0, 2, 1f, 1));
            }
        }
        var score = new ScorePageInterpretation(bars, notes, 0, List.of(), List.of(),
                List.of(new ScoreMeterChange(0, 4, 4)), List.of());
        assertEquals(2, ScoreOpeningDuration.provedQuarterBeats(withPhysicalPage(score, 0), 4), 0);
        assertTrue(Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(withPhysicalPage(score, 1), 4)));
        assertTrue(Double.isNaN(ScoreOpeningDuration.provedQuarterBeats(score, 4)));
    }
}
