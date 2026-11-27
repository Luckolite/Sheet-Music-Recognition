// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class WholeColumnEndTest {
    private static final int WIDTH = 400, HEIGHT = 230, X = 194, GAP = 16, TOP = 96;

    @Test
    public void extendsAnAlignedColumnOfWholeNotesIntoSymbolOnlyInk() {
        Page page = new Page(true, true);
        var notes = OmrScoreInterpreter.extract(page.labels, page.raster, WIDTH, HEIGHT,
                List.of(new MeasureRegion(.12f, .88f, .36f, .72f)));
        assertEquals(List.of(3, 5, 7), notes.stream().map(ScoreNoteEvent::staffStep).sorted().toList());
        for (var note : notes) assertEquals(4.0f, note.unbeamedDurationBeats(), 0);
    }

    @Test
    public void refusesAnOvalMissingFromTheOriginalRaster() {
        Page prepared = new Page(true, true);
        Page filledOriginal = new Page(true, false);
        assertNull(columnEnd(prepared, filledOriginal.raster));
    }

    @Test
    public void requiresAnIndependentSecondNoteheadLabel() {
        Page page = new Page(false, true);
        assertNull(columnEnd(page, page.raster));
    }

    private static SeededWholeHeadRecovery.Head columnEnd(Page page, byte[] original) {
        return SeededWholeHeadRecovery.wholeColumnEnd(page.labels, page.raster, original,
                WIDTH, HEIGHT, GAP, TOP, TOP + 4 * GAP, X, TOP + GAP * 1.5f, -1);
    }

    private static final class Page {
        final byte[] labels = new byte[WIDTH * HEIGHT];
        final byte[] raster = new byte[labels.length];

        Page(boolean lowerHasLabel, boolean topIsOpen) {
            Arrays.fill(raster, (byte) 255);
            for (int y = TOP; y <= TOP + 4 * GAP; y += GAP)
                for (int x = 22; x <= 374; x++) {
                    labels[y * WIDTH + x] = OmrMeasurePostProcessor.STAFF;
                    raster[y * WIDTH + x] = 0;
                }
            oval(TOP + 8, OmrMeasurePostProcessor.SYMBOL, topIsOpen);
            oval(TOP + 24, OmrMeasurePostProcessor.NOTEHEAD, true);
            oval(TOP + 40, lowerHasLabel
                    ? OmrMeasurePostProcessor.NOTEHEAD : OmrMeasurePostProcessor.SYMBOL, true);
        }

        private void oval(int cy, byte label, boolean hollow) {
            for (int dy = -8; dy <= 8; dy++)
                for (int dx = -13; dx <= 13; dx++) {
                    if (dx * dx / 169d + dy * dy / 64d > 1) continue;
                    int at = (cy + dy) * WIDTH + X + dx;
                    if (Math.abs(dy) < 7) labels[at] = label;
                    double innerX = dx - .35 * dy;
                    if (!hollow || innerX * innerX / 25d + dy * dy / 42d >= 1)
                        raster[at] = 0;
                }
        }
    }
}
