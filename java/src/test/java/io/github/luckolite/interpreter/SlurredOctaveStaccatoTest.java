// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SlurredOctaveStaccatoTest {
    private static final int W = 700, H = 400, GAP = 16, STAFF_BOTTOM = 264;
    private static final int FIRST = 84, SPACING = 32, LEFT = 45, RIGHT = 595;
    private static final int DOTTED = 13, NEXT = DOTTED + 1;

    @Test
    public void insetMarkBelongsToTheLowerSlurEndpoint() {
        byte[] ink = ink();
        var score = score(true);
        var changed = apply(score, ink, ink);
        for (int i = 0; i < score.notes().size(); i++) {
            var old = score.notes().get(i);
            var found = changed.notes().get(i);
            assertEquals(i / 2 == DOTTED ? 0 : old.augmentationDots(), found.augmentationDots());
            assertEquals(i == NEXT * 2 + 1 ? NoteArticulation.STACCATO : 0, found.articulations());
            assertEquals(old.beamCount(),found.beamCount());
        }
        var unowned = score(false);
        assertSame(unowned, apply(unowned, ink, ink));

        byte[] missing = ink.clone();
        draw(missing, x(NEXT) - 13, x(NEXT) - 7, 285, 291, (byte) 255);
        assertSame(score, apply(score, missing, ink));
        assertSame(score, apply(score, ink, missing));
    }

    private static ScorePageInterpretation apply(ScorePageInterpretation score, byte[] ink, byte[] raw) {
        return ClosedSixteenthDyads.apply(score, new ScoreMeterChange(0, 4, 4), ink, raw, W, H);
    }

    private static ScorePageInterpretation score(boolean arcOwned) {
        var bar = new MeasureRegion((float) LEFT / W, (float) RIGHT / W,
                (STAFF_BOTTOM - 97f) / H, (STAFF_BOTTOM + 34f) / H);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 16; i++) {
            int low = lower(i);
            for (int y : new int[] {low - 56, low})
                notes.add(new ScoreNoteEvent(0, (float) (x(i) - LEFT) / (RIGHT - LEFT),
                        Math.floorDiv(STAFF_BOTTOM - y, 8), 0, 1, (float) y / H, false,
                        i == DOTTED ? 1 : 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0)
                        .withClef(ScoreNoteEvent.CLEF_TREBLE).withStemDirection(1));
        }
        var score = new ScorePageInterpretation(List.of(bar), notes);
        if (!arcOwned) return score;
        var arc = new ScoreSlurPages.Span("original-below",
                new ScoreSlurPages.Anchor(0, (DOTTED - 1) * 2 + 1),
                new ScoreSlurPages.Anchor(0, NEXT * 2 + 1), ScoreSlurSpan.Placement.BELOW,
                ScoreSlurSpan.LineType.SOLID, List.of(new ScoreSlurSpan.ArcEvidence(0,
                        (float) x(DOTTED - 1) / W, 287f / H, (float) x(NEXT) / W, 294f / H)));
        return score.withSlurSource(ScoreSlurSource.capture(List.of(), List.of(arc),
                List.of(new ScoreSlurPages.Page(0, notes.size()))));
    }

    private static int x(int slot) {
        return FIRST + slot * SPACING;
    }

    private static int lower(int slot) {
        return slot % 2 == 1 ? 282 : 274;
    }

    private static byte[] ink() {
        byte[] ink = new byte[W * H];
        Arrays.fill(ink, (byte) 255);
        for (int y = STAFF_BOTTOM - 4 * GAP; y <= STAFF_BOTTOM; y += GAP)
            draw(ink, 18, W - 20, y, y + 1, (byte) 0);
        for (int i = 0; i < 16; i++) {
            int x = x(i), low = lower(i);
            for (int y : new int[] {low - 56, low})
                draw(ink, x - 8, x + 8, y - 4, y + 4, (byte) 0);
            int shaft = x + 9;
            draw(ink, shaft, shaft + 2, 165, low, (byte) 0);
            if (i % 4 != 3) {
                draw(ink, shaft, shaft + SPACING + 2, 165, 170, (byte) 0);
                draw(ink, shaft, shaft + SPACING + 2, 178, 183, (byte) 0);
            }
        }
        for (int x = x(DOTTED - 1); x <= x(NEXT); x++) {
            int d = x - x(DOTTED - 1);
            int y = 289 + Math.round(d * .08f + 6 * (float)Math.sin(Math.PI * d / (2 * SPACING)));
            draw(ink, x, x, y, y + 1, (byte) 0);
        }
        draw(ink, x(NEXT) - 12, x(NEXT) - 8, 286, 290, (byte) 0);
        return ink;
    }

    private static void draw(byte[] ink, int left, int right, int top, int bottom, byte tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * W + left, y * W + right + 1, tone);
    }
}
