// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class ScorePageTimelineTest {
    @Test
    public void missedMeasureOneKeepsUnknownPageWithRestartedPart() {
        int[] counts = {30, 28, 15, 12, 10, 11};
        int[] starts = {1, 31, 59, 0, 10, 20};
        var firstPart = new ScorePageTimeline.PageRange(0, 3);
        var restartedPart = new ScorePageTimeline.PageRange(3, 6);

        assertEquals(
                List.of(firstPart, restartedPart), ScorePageTimeline.arrangements(counts, starts));
        assertEquals(restartedPart, ScorePageTimeline.selectedArrangement(counts, starts, 3));
        assertEquals(3, ScorePageTimeline.pageForMeasure(0, counts, starts, restartedPart));
    }

    @Test
    public void ordinaryUnknownContinuationDoesNotCreateAnotherPart() {
        assertEquals(
                List.of(new ScorePageTimeline.PageRange(0, 3)),
                ScorePageTimeline.arrangements(new int[] {12, 8, 7}, new int[] {1, 0, 21}));
    }

    /** Original small integer examples, unrelated to a source document's bar totals. */
    @Test
    public void explicitChoiceUsesEveryPageOfTheSelectedArrangement() {
        int[] counts = {4, 3, 3, 4}, starts = {1, 5, 1, 4};
        var second = new ScorePageTimeline.PageRange(2, 4);
        assertEquals(
                List.of(new ScorePageTimeline.PageRange(0, 2), second),
                ScorePageTimeline.arrangements(counts, starts));
        assertEquals(second, ScorePageTimeline.selectedArrangement(counts, starts, 3));
        assertEquals(7, ScorePageTimeline.measureCount(counts, starts, second));
        assertEquals(2, ScorePageTimeline.pageForMeasure(0, counts, starts, second));
        assertEquals(3, ScorePageTimeline.pageForMeasure(6, counts, starts, second));
    }

    @Test
    public void disabledPagesRebaseTheSelectedArrangementWithoutEmptyChoices() {
        int[] counts = {0, 0, 3, 4}, starts = {1, 5, 9, 12};
        var selected = new ScorePageTimeline.PageRange(2, 4);
        assertEquals(List.of(selected), ScorePageTimeline.arrangements(counts, starts));
        assertEquals(selected, ScorePageTimeline.selectedArrangement(counts, starts, 99));
        // Outside the selected range offsets retain relative printed coordinates; they
        // are not playback pages. Only enabled pages in the range form the dense clock.
        org.junit.Assert.assertArrayEquals(
                new int[] {-8, -4, 0, 3},
                ScorePageTimeline.playbackOffsets(counts, starts, selected));
        assertEquals(7, ScorePageTimeline.measureCount(counts, starts, selected));
        assertEquals(2, ScorePageTimeline.pageForMeasure(0, counts, starts, selected));
        assertEquals(3, ScorePageTimeline.pageForMeasure(6, counts, starts, selected));
        assertEquals(-1, ScorePageTimeline.pageForMeasure(-1, counts, starts, selected));
        assertEquals(-1, ScorePageTimeline.pageForMeasure(7, counts, starts, selected));
    }

    @Test
    public void oneBarOverlapDoesNotDropTheFinalPlaybackBar() {
        int[] counts = {6, 5}, starts = {0, 6};
        var selected = new ScorePageTimeline.PageRange(0, 2);
        org.junit.Assert.assertArrayEquals(
                new int[] {0, 6}, ScorePageTimeline.playbackOffsets(counts, starts, selected));
        assertEquals(11, ScorePageTimeline.measureCount(counts, starts, selected));
        assertEquals(1, ScorePageTimeline.pageForMeasure(10, counts, starts, selected));
    }

    @Test
    public void consecutiveUnnumberedOpeningPagesStayWithTheRestartedEdition() {
        int[] counts = {24, 18, 9, 11, 9, 12};
        var first = new ScorePageTimeline.PageRange(0, 2);
        var second = new ScorePageTimeline.PageRange(2, 6);
        assertEquals(java.util.List.of(first, second),
                ScorePageTimeline.arrangements(counts, new int[] {48, 72, 0, 0, 21, 30}));

        assertEquals(java.util.List.of(new ScorePageTimeline.PageRange(0, 6)),
                ScorePageTimeline.arrangements(counts, new int[] {48, 72, 0, 0, 4, 108}));
    }

    @Test
    public void unnumberedFinalPageDoesNotMoveIntoTheNextEdition() {
        int[] counts = {22, 19, 2, 8, 16, 15};
        int[] starts = {31, 53, 0, 0, 9, 25};
        assertEquals(java.util.List.of(new ScorePageTimeline.PageRange(0, 3),
                        new ScorePageTimeline.PageRange(3, 6)),
                ScorePageTimeline.arrangements(counts, starts));
    }
}
