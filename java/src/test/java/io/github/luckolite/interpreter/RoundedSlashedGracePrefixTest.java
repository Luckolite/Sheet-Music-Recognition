// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original procedural geometry, without any source-score pixels or coordinates. */
public class RoundedSlashedGracePrefixTest {
    List<ScoreNoteEvent> example(
            boolean slash,
            boolean flag,
            boolean shaft,
            int distance,
            int principalArea,
            int staff,
            boolean dotted)
            throws Exception {
        return example(slash, flag, shaft, distance, principalArea, staff, dotted, 115);
    }

    private List<ScoreNoteEvent> example(
            boolean slash, boolean flag, boolean shaft, int distance, int principalArea, int staff,
            boolean dotted, int principalY)
            throws Exception {
        var h = new FadedGraceOwnershipTest();
        if (shaft) h.rect(104, 105, 85, 120, 30);
        if (flag) h.line(104, 85, 113, 101, 1, 30);
        if (slash) h.line(98, 106, 115, 89, 1, 30);
        var first = new ScoreNoteEvent(0, 96 / 300f, 3, 0, 1, .5f, false, dotted ? 1 : 0, 1, 2, 0);
        return h.mark(
                List.of(
                        h.head(96, 120, 8, 7, 170),
                        h.head(96 + distance, principalY, 12, 9, principalArea)),
                List.of(first, h.event(96 + distance, 0, staff)),
                "markSlashedGracePrefixes");
    }

    boolean grace(List<ScoreNoteEvent> ns) {
        return (ns.get(0).articulations() & NoteOrnament.GRACE) != 0;
    }

    private List<ScoreNoteEvent> returningFlag(boolean hook, boolean opposingPrincipal)
            throws Exception {
        var h = new FadedGraceOwnershipTest();
        h.rect(104, 105, 84, 120, 30);
        if (hook) {
            h.line(104, 84, 113, 102, 1, 30);
            h.line(113, 102, 112, 113, 1, 30);
        }
        if (opposingPrincipal) h.rect(120, 121, 125, 165, 30);
        else h.rect(143, 144, 85, 125, 30);
        return h.mark(List.of(h.head(96, 120, 8, 7, 170), h.head(132, 125, 12, 9, 360)),
                List.of(h.event(96, 1, 0), h.event(132, 0, 0)), "markSlashedGracePrefixes", true);
    }

    @Test
    public void printedSlashCanLeadAcrossRegisterLeap() throws Exception {
        assertTrue(grace(example(true, true, true, 36, 360, 0, false, 32)));
        assertFalse(grace(example(false, true, true, 36, 360, 0, false, 32)));
        assertFalse(grace(example(true, true, true, 36, 360, 0, false, 20)));
    }

    @Test
    public void independentReturningFlagAndOpposingPrincipalProveUnslashedGrace() throws Exception {
        assertTrue(grace(returningFlag(true, true)));
        assertFalse(grace(returningFlag(false, true)));
        assertFalse(grace(returningFlag(true, false)));
    }

    @Test
    public void roundedHeadWithIndependentSlashFlagAndShortShaftIsGrace() throws Exception {
        assertTrue(grace(example(true, true, true, 36, 360, 0, false)));
    }

    @Test
    public void missingSlashKeepsSmallMetricalNote() throws Exception {
        assertFalse(grace(example(false, true, true, 36, 360, 0, false)));
    }

    @Test
    public void missingFlagRejectsSlashFragment() throws Exception {
        assertFalse(grace(example(true, false, true, 36, 360, 0, false)));
    }

    @Test
    public void detachedInkNeedsItsOwnShaft() throws Exception {
        assertFalse(grace(example(true, true, false, 36, 360, 0, false)));
    }

    @Test
    public void differentStaffCannotOwnGrace() throws Exception {
        assertFalse(grace(example(true, true, true, 36, 360, 1, false)));
    }

    @Test
    public void distantPrincipalCannotOwnGrace() throws Exception {
        assertFalse(grace(example(true, true, true, 70, 360, 0, false)));
    }

    @Test
    public void dottedNoteStaysMetrical() throws Exception {
        assertFalse(grace(example(true, true, true, 36, 360, 0, true)));
    }

    @Test
    public void equallySmallPrincipalDoesNotProveGrace() throws Exception {
        assertFalse(grace(example(true, true, true, 36, 180, 0, false)));
    }
}
