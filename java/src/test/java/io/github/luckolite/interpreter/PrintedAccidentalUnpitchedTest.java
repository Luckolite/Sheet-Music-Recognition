// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertSame;

import java.util.List;
import org.junit.Test;

/** Synthetic mixed pitched and unpitched attacks in the same measure. */
public class PrintedAccidentalUnpitchedTest {
    private static final int WIDTH = 200;
    private static final int HEIGHT = 200;
    private final byte[] pixels = new byte[WIDTH * HEIGHT];

    @Test
    public void sixthRecoverySkipsUnpitchedAttacks() {
        var page = page(note(.2f, 3), note(.2f, 8), unpitched(.5f), note(.5f, 9));

        assertSame(page, PrintedAccidentalSpineRecovery.reconcileDyadSigns(
                page, pixels, pixels, pixels, WIDTH, HEIGHT, mark -> {
                    throw new AssertionError("Unpitched attack cannot own an accidental");
                }));
    }

    @Test
    public void thirdRecoverySkipsUnpitchedAttacks() {
        var page = page(note(.2f, 3), note(.5f, 4), unpitched(.5f));

        assertSame(page, PrintedAccidentalSpineRecovery.reconcileSpacedThirds(
                page, pixels, pixels, pixels, WIDTH, HEIGHT, mark -> {
                    throw new AssertionError("Unpitched attack cannot own an accidental");
                }));
    }

    private static ScorePageInterpretation page(ScoreNoteEvent... notes) {
        return new ScorePageInterpretation(
                List.of(new MeasureRegion(.1f, .9f, .1f, .756f)), List.of(notes));
    }

    private static ScoreNoteEvent note(float x, int step) {
        return new ScoreNoteEvent(0, x, step, 0, 1, .5f, false, 0, 2,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0).withStemDirection(-1);
    }

    private static ScoreNoteEvent unpitched(float x) {
        return note(x, 0).withKind(ScoreNoteEvent.Kind.UNPITCHED);
    }
}
