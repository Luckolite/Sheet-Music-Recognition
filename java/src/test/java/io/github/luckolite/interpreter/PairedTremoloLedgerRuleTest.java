// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Synthetic ledger rules and finite marks around two chord columns. */
public class PairedTremoloLedgerRuleTest {
    private static final double OTHER_X = 40;
    private static final PairedTremoloInk.Head ENDPOINT = new PairedTremoloInk.Head(
            1, 0, 0, 120, 60, 16, 1, List.of(), true, 120, null, 125, 60, 20);

    @Test
    public void shortOffStaffLinesAroundOneChordAreLedgerRules() {
        assertTrue(PairedTremoloInk.endpointLedgerFamily(
                mark(108, 128, 0, 72, 88), ENDPOINT, OTHER_X));
    }

    @Test
    public void aCenteredFamilyIsStillEligibleForPairedTremolo() {
        assertFalse(PairedTremoloInk.endpointLedgerFamily(
                mark(66, 94, 0, 72, 88), ENDPOINT, OTHER_X));
    }

    @Test
    public void shortUnevenOrSlopedDetailsNeedIndependentClassification() {
        assertFalse(PairedTremoloInk.endpointLedgerFamily(
                mark(108, 128, 0, 72, 81), ENDPOINT, OTHER_X));
        assertFalse(PairedTremoloInk.endpointLedgerFamily(
                mark(108, 128, .2, 72, 88), ENDPOINT, OTHER_X));
    }

    private static PairedTremoloInk.Mark mark(int left, int right, double slope,
            double top, double bottom) {
        return new PairedTremoloInk.Mark(left, right, slope, List.of(
                new PairedTremoloInk.Band(top, 2), new PairedTremoloInk.Band(bottom, 2)));
    }
}
