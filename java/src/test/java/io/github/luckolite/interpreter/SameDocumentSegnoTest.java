// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static io.github.luckolite.interpreter.ScorePlaybackDirection.Kind.*;
import static org.junit.Assert.*;

public final class SameDocumentSegnoTest {
    private static final String DOCUMENT = "a".repeat(64);
    private static final ScorePageTimeline.PageRange DONOR = new ScorePageTimeline.PageRange(0, 1);
    private static final ScorePageTimeline.PageRange TARGET = new ScorePageTimeline.PageRange(1, 2);

    @Test
    public void transfersOnlyMissingSignAcrossMatchedEditions() {
        var bars = bars();
        assertEquals(new SameDocumentSegno.Transfer(1, 3), resolve(bars, bars, DOCUMENT, 1, 10));
        var split = new ArrayList<>(bars);
        var closing = split.remove(9).get(0);
        split.add(9, List.of(closing.subList(0, 2)));
        split.add(10, List.of(closing.subList(2, closing.size())));
        var donor = new SameDocumentSegno.Page(DOCUMENT, 0, 2, 1, directions(true, 10, 15), bars);
        // Use distinct pages for the Coda and command in a split edition so their
        // independently printed starting numbers are still authoritative.
        var beforeCommand = new SameDocumentSegno.Page(DOCUMENT, 1, 3, 1,
                List.of(new ScorePlaybackDirection(11, TO_CODA)), split.subList(0, 14));
        var pageAfterSplit = new SameDocumentSegno.Page(DOCUMENT, 2, 3, 14,
                List.of(new ScorePlaybackDirection(2, DAL_SEGNO_AL_CODA), new ScorePlaybackDirection(2, CODA)),
                bars.subList(13, bars.size()));
        var splitDonor = new SameDocumentSegno.Page(DOCUMENT, 0, 3, 1, donor.directions(), bars);
        var splitRange = new ScorePageTimeline.PageRange(1, 3);
        assertEquals(new SameDocumentSegno.Transfer(1, 3), SameDocumentSegno.resolve(
                List.of(splitDonor, beforeCommand, pageAfterSplit), List.of(DONOR, splitRange), splitRange));
    }

    @Test
    public void rejectsMissingOriginAndDifferentlyNumberedOrAmbiguousMusic() {
        var bars = bars();
        assertNull(resolve(bars, bars, "", 1, 10));
        assertNull(resolve(bars, bars, "b".repeat(64), 1, 10));
        assertNull(resolve(bars, bars, DOCUMENT, 2, 10));
        var source = new SameDocumentSegno.Page(DOCUMENT, 0, 2, 1, directions(true, 10, 15), bars);
        var early = new SameDocumentSegno.Page(DOCUMENT, 1, 2, 1, directions(false, 9, 15), bars);
        assertNull(SameDocumentSegno.resolve(List.of(source, early), List.of(DONOR, TARGET), TARGET));
        var altered = new ArrayList<>(bars);
        altered.set(9, List.of(List.of(new SameDocumentSegno.Attack(90, 2, 0))));
        assertNull(resolve(bars, altered, DOCUMENT, 1, 10));
    }

    @Test
    public void refusesDuplicateMatchingSegnoWindowsEvenAtTheSamePrintedNumber() {
        var bars = bars();
        var repeated = new ArrayList<>(bars);
        for (int i = 0; i < 4; i++) repeated.set(6 + i, bars.get(2 + i));
        // Put the trigger beyond the second eligible window.
        assertNull(resolve(bars, repeated, DOCUMENT, 1, 12));
    }

    private static SameDocumentSegno.Transfer resolve(List<List<List<SameDocumentSegno.Attack>>> donor,
            List<List<List<SameDocumentSegno.Attack>>> receiver, String digest, int start, int trigger) {
        var source = new SameDocumentSegno.Page(DOCUMENT, 0, 2, 1, directions(true, trigger, 15), donor);
        var target = new SameDocumentSegno.Page(digest, 1, 2, start, directions(false, trigger, 15), receiver);
        return SameDocumentSegno.resolve(List.of(source, target), List.of(DONOR, TARGET), TARGET);
    }

    private static List<ScorePlaybackDirection> directions(boolean donor, int trigger, int command) {
        var rows = new ArrayList<ScorePlaybackDirection>();
        if (donor) rows.add(new ScorePlaybackDirection(3, SEGNO));
        rows.add(new ScorePlaybackDirection(trigger, TO_CODA));
        rows.add(new ScorePlaybackDirection(command, DAL_SEGNO_AL_CODA));
        rows.add(new ScorePlaybackDirection(command, CODA));
        return rows;
    }

    private static List<List<List<SameDocumentSegno.Attack>>> bars() {
        var result = new ArrayList<List<List<SameDocumentSegno.Attack>>>();
        for (int m = 0; m < 24; m++) {
            var staff = new ArrayList<SameDocumentSegno.Attack>();
            for (int n = 0; n < 6; n++) staff.add(new SameDocumentSegno.Attack(20 + m + n * 2, 2, 0));
            result.add(List.of(staff));
        }
        return result;
    }
}
