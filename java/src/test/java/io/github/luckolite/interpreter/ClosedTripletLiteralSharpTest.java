// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ClosedTripletLiteralSharpTest {
    @Test
    public void completeSharpOwnsOnlyTheAlignedHead() {
        var page = new SharpPitchAlignmentTest.Page(124, 124);
        assertTrue(owns(page, 280, 124));
        assertFalse(owns(page, 280, 116));
        assertFalse(owns(page, 280, 132));
        assertFalse(owns(page, 330, 124));
    }

    @Test
    public void inkAndSourceClassAreBothRequired() {
        var noClass = new SharpPitchAlignmentTest.Page(124, 124);
        for (int i = 0; i < noClass.labels.length; i++)
            if (noClass.labels[i] == OmrMeasurePostProcessor.CLEF_OR_KEY) noClass.labels[i] = 0;
        assertFalse(owns(noClass, 280, 124));

        var noInk = new SharpPitchAlignmentTest.Page(124, 124);
        for (int y = 102; y <= 146; y++)
            Arrays.fill(
                    noInk.gray,
                    y * SharpPitchAlignmentTest.W + 248,
                    y * SharpPitchAlignmentTest.W + 264,
                    (byte) 255);
        assertFalse(owns(noInk, 280, 124));
    }

    @Test
    public void typedStemAndOneCrossbarDoNotOwnASharp() {
        var page = new SharpPitchAlignmentTest.Page(124, 124);
        for (int y = 121; y <= 146; y++)
            for (int x = 248; x <= 263; x++)
                if (x != 251 && x != 252 && x != 259 && x != 260)
                    page.gray[y * SharpPitchAlignmentTest.W + x] = (byte) 255;
        assertFalse(owns(page, 280, 124));
    }

    @Test
    public void exportRemapsOnlyAUniqueMatchingPhysicalHead() {
        var target =
                new ScoreNoteEvent(0, .35f, 2, 0, 1, .44f, false, 0, 1, 1, 0)
                        .withClef(ScoreNoteEvent.CLEF_TREBLE);
        var other =
                new ScoreNoteEvent(0, .2f, 0, 0, 1, .48f, false, 0, 1, 0, 0)
                        .withClef(ScoreNoteEvent.CLEF_TREBLE);
        var known = List.of(new SourceLocalAccidental.PrintedMark(0, 0));
        var recovered =
                List.of(
                        new SourceLocalAccidental.PrintedMark(0, 0),
                        new SourceLocalAccidental.PrintedMark(1, 1));
        assertEquals(
                recovered,
                SourceLocalAccidental.includeClosedTripletSharps(
                        known, List.of(target), List.of(other, target.withTupletRatio(3, 2))));
        assertEquals(
                known,
                SourceLocalAccidental.includeClosedTripletSharps(
                        known, List.of(target), List.of(other, target, target)));
        assertEquals(
                known,
                SourceLocalAccidental.includeClosedTripletSharps(
                        known, List.of(target), List.of(other, target.withClef(18))));
        assertEquals(
                known,
                SourceLocalAccidental.includeClosedTripletSharps(
                        known, List.of(target), List.of(other)));
    }

    private static boolean owns(SharpPitchAlignmentTest.Page page, float x, float y) {
        return WrappedEighthTriplets.closedBarHasPrintedSharp(
                page.gray,
                page.labels,
                SharpPitchAlignmentTest.W,
                SharpPitchAlignmentTest.H,
                x,
                y,
                16);
    }
}
