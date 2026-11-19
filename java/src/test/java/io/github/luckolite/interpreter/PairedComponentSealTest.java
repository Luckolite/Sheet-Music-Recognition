// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;

import org.junit.Test;

public class PairedComponentSealTest {
    @Test
    public void changesToEitherPlaneInvalidateBothCollectors() {
        for (boolean changeRaster : new boolean[] {true, false}) {
            byte[] raw = {1, 2, 3}, labels = {0, 2, 0};
            var pair = collect(raw, labels, budget(6));
            (changeRaster ? raw : labels)[0] ^= 1;
            pair.seal(budget(6), budget(6));
            assertFalse(pair.first.seal(budget(0)));
            assertFalse(pair.second.seal(budget(0)));
        }
    }

    @Test
    public void eachCollectorSpendsItsOwnBudgetAndRevalidatesLater() {
        byte[] raw = {1, 2, 3}, labels = {0, 2, 0};
        var pair = collect(raw, labels, budget(6));
        var firstSeal = budget(5);
        var secondSeal = budget(6);
        pair.seal(firstSeal, secondSeal);
        assertFalse(pair.first.seal(budget(0)));
        assertTrue(pair.second.seal(budget(0)));
        assertEquals(5, firstSeal.work);
        assertEquals(0, secondSeal.work);
        labels[1] ^= 1;
        assertFalse(pair.second.revalidateForExport(budget(6)));
    }

    @Test
    public void secondCollectorCanSealWhenTheFirstCannotAuthenticate() {
        byte[] raw = {1, 2, 3}, labels = {0, 2, 0};
        var pair = collect(raw, labels, budget(5));
        pair.seal(budget(6), budget(6));
        assertFalse(pair.first.seal(budget(0)));
        assertTrue(pair.second.seal(budget(0)));
    }

    private static ExactComponentMembers.CollectorPair collect(
            byte[] raw, byte[] labels, ExactComponentMembers.Budget firstAuth) {
        return new ExactComponentMembers.CollectorPair(
                raw, labels, raw.length, 1, firstAuth, budget(6), budget(100), budget(100));
    }

    private static ExactComponentMembers.Budget budget(long work) {
        return new ExactComponentMembers.Budget(work, 4096);
    }
}
