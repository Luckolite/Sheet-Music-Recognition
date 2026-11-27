// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Synthetic paired rest lanes admitted from independently owned printed counts. */
public class PairedPrintedRestNotationTest {
    private static final int W = 900, H = 510, LEFT = 70, RIGHT = 270;

    private record Result(List<ScoreRestEvent> rests, List<ScoreMultiRestGroup> groups) {}

    private void rect(byte[] gray, int l, int t, int r, int b) {
        for (int y = t; y <= b; y++) Arrays.fill(gray, y * W + l, y * W + r + 1, (byte) 10);
    }

    private SelectedMeasureOrigins.PhysicalMember staff(byte[] gray, int top, boolean body) {
        int[] rows = {top, top + 16, top + 32, top + 48, top + 64};
        for (int y : rows) rect(gray, LEFT, y, RIGHT, y);
        if (body) rect(gray, 178, top + 26, 235, top + 38);
        rect(gray, 198, top - 35, 215, top - 31);
        for (int y = 0; y <= 16; y++) rect(gray, 213 - y, top - 30 + y, 218 - y, top - 30 + y);
        rect(gray, 195, top - 14, 215, top - 11);
        return new SelectedMeasureOrigins.PhysicalMember(new Object(), rows, 16,
                LEFT, RIGHT, 0, null);
    }

    private MeasureNumberReconciler.NumberToken count(int top, int value) {
        return new MeasureNumberReconciler.NumberToken(
                value, 195f / W, (top - 35f) / H, 218f / W, (top - 11f) / H);
    }

    private Result project(int secondCount, boolean secondBody, boolean hasOrdinaryRest) {
        byte[] gray = new byte[W * H], labels = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        var pair = List.of(staff(gray, 120, true), staff(gray, 290, secondBody));
        var input = new MeasureRegion(150f / W, 263f / W, 96f / H, 378f / H);
        var raw = List.of(input);
        var logical = List.of(input, input);
        var source = new SelectedMeasureOrigins();
        source.begin(labels, gray, W, H);
        source.emitted(new Object(), pair, input, LEFT, RIGHT);
        source.prepared(labels);
        var rests = new RecognizedMultiRestOrigins(source);
        rests.begin(labels, gray, W, H, raw);
        var upper = count(120, 2);
        rests.admitted(upper, new MultiMeasureRestDetector.RestBarCandidate(0, input),
                0, secondCount == 0 ? List.of(upper) : List.of(upper, count(290, secondCount)));
        rests.expanded(raw, logical);
        var existing = hasOrdinaryRest
                ? List.of(ScoreRestEvent.fullMeasure(0, .4f, .3f, .02f, 0, 2))
                : List.<ScoreRestEvent>of();
        var score = RecognizedMultiRestProjection.apply(
                new OmrScoreInterpreter.Analysis(List.of(), List.of(), existing, null),
                logical, labels, gray, W, H, rests);
        return new Result(score.rests(),
                PrintedMultiRestGroups.capture(score, logical, labels, gray, W, H, rests));
    }

    @Test public void bothLanesProduceGroupsAndFourExplicitRests() {
        var result = project(2, true, false);
        assertEquals(List.of(new ScoreMultiRestGroup(0, 2, 0, 2),
                new ScoreMultiRestGroup(0, 2, 1, 2)), result.groups());
        assertEquals(4, result.rests().size());
        for (int i = 0; i < 4; i++) {
            var event = result.rests().get(i);
            assertEquals(i / 2, event.measureIndex());
            assertEquals(i % 2, event.staffIndex());
            assertTrue(event.isFullMeasure());
        }
    }

    @Test public void missingOrConflictingSecondCountCannotCreateAGroup() {
        assertTrue(project(0, true, false).groups().isEmpty());
        assertTrue(project(3, true, false).groups().isEmpty());
    }

    @Test public void matchingCountsWithoutTwoRestBarsAreInsufficient() {
        assertTrue(project(2, false, false).rests().isEmpty());
    }

    @Test public void existingPartialRestLaneCannotBeOverwrittenOrDuplicated() {
        var result = project(2, true, true);
        assertEquals(1, result.rests().size());
        assertTrue(result.groups().isEmpty());
    }
}
