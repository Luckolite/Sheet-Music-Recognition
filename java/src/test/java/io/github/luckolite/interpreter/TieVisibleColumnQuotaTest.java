// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class TieVisibleColumnQuotaTest {
    @Test
    public void countsRepeatedVotesAndExcludesStraightRulesAndNoteheads() {
        int width = 60;
        byte[] gray = new byte[width * 4], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        Arrays.fill(gray, width, 2 * width, (byte) 0);
        gray[2 * width + 3] = gray[2 * width + 5] = 0;
        var rows = new DenseTieArcContinuity.StraightRows(gray, width, 1, 55, 180, 0, 3);
        int[] columns = new int[50];
        Arrays.fill(columns, 0, 10, 3);
        Arrays.fill(columns, 10, 27, 4);
        Arrays.fill(columns, 27, columns.length, 5);
        assertFalse(rows.canSupplyVisibleVotes(labels, columns, 0, 3));
        columns[26] = 5;
        assertTrue(rows.canSupplyVisibleVotes(labels, columns, 0, 3));
        Arrays.fill(columns, 0, 8, 1);
        Arrays.fill(columns, 10, columns.length, 5);
        assertFalse(rows.canSupplyVisibleVotes(labels, columns, 0, 3));
        columns[7] = 3;
        assertTrue(rows.canSupplyVisibleVotes(labels, columns, 0, 3));
        labels[2 * width + 3] = OmrMeasurePostProcessor.NOTEHEAD;
        assertFalse(rows.canSupplyVisibleVotes(labels, columns, 0, 3));
    }
}
