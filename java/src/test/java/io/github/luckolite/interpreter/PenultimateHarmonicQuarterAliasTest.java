// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class PenultimateHarmonicQuarterAliasTest {
    private static final int WIDTH = 360;
    private static final int HEIGHT = 250;
    private static final int FIRST = 110;
    private static final int SECOND = 158;
    private static final int UPPER = 160;
    private static final int LOWER = 192;
    private static final int TOUCH = 176;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final byte[] labels = new byte[gray.length];

    @Test
    public void sharedStoppedShaftConsumesTheHollowTouch() {
        draw();
        var before = score();
        var after = PenultimateHarmonicQuarterAlias.capture(
                before, labels, gray, gray, WIDTH, HEIGHT);
        assertEquals(before.measures(), after.measures());
        assertEquals(3, after.notes().size());
        assertEquals(0, after.notes().get(0).beamCount());
        assertEquals(1f, after.notes().get(0).unbeamedDurationBeats(), 0f);
        assertEquals(before.notes().subList(2, 4), after.notes().subList(1, 3));
    }

    @Test
    public void brokenShaftAndFilledTouchKeepTheOriginalOwners() {
        draw();
        var before = score();
        byte[] changed = gray.clone();
        for (int y = 180; y <= 184; y++)
            Arrays.fill(changed, y * WIDTH + 166, y * WIDTH + 171, (byte) 255);
        assertSame(before, PenultimateHarmonicQuarterAlias.capture(
                before, labels, gray, changed, WIDTH, HEIGHT));
        changed = gray.clone();
        for (int y = TOUCH - 4; y <= TOUCH + 4; y++)
            Arrays.fill(changed, y * WIDTH + SECOND - 6, y * WIDTH + SECOND + 7, (byte) 0);
        assertSame(before, PenultimateHarmonicQuarterAlias.capture(
                before, labels, gray, changed, WIDTH, HEIGHT));
    }

    private ScorePageInterpretation score() {
        return new ScorePageInterpretation(
                List.of(region(25, 81), region(85, 209), region(217, 297)),
                List.of(note(FIRST,120,6,1,0,-1,2),
                        note(SECOND-2,TOUCH,-1,0,2,1,0),
                        note(SECOND,UPPER,1,0,1,0,0),
                        note(SECOND,LOWER,-3,0,1,1,2)),
                0, List.of(), List.of(), List.of(new ScoreMeterChange(0, 2, 4)),
                List.of(), List.of(), List.of(), List.of());
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion(left/(float) WIDTH, right/(float) WIDTH,
                68f/HEIGHT, 199.2f/HEIGHT);
    }

    private static ScoreNoteEvent note(int x, int y, int step, int beams,
            float duration, int stem, int octave) {
        return new ScoreNoteEvent(1, (x-85f)/124f, step, 0, 1, y/(float) HEIGHT,
                false, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, duration, 1, 0, 0,
                ScoreNoteEvent.CLEF_TREBLE, false, 0, false, octave, 0, 1, stem);
    }

    private void draw() {
        Arrays.fill(gray, (byte) 255);
        for (int y = 104; y <= 168; y += 16)
            rect(28, y-1, 299, y+1, OmrMeasurePostProcessor.STAFF);
        rect(84, 104, 86, 168, OmrMeasurePostProcessor.STEM_OR_REST);
        rect(212, 104, 214, 168, OmrMeasurePostProcessor.STEM_OR_REST);
        rect(140, 183, 177, 184, OmrMeasurePostProcessor.STAFF);
        diamond(FIRST, 96, 10);
        diamond(SECOND, 136, 11);
        diamond(SECOND-2, TOUCH, 11);
        ellipse(FIRST,120);
        ellipse(SECOND,UPPER);
        ellipse(SECOND,LOWER);
        rect(99, 96, 101, 182, OmrMeasurePostProcessor.STEM_OR_REST);
        rect(167, 85, 169, 193, OmrMeasurePostProcessor.STEM_OR_REST);
    }

    private void diamond(int cx, int cy, int radius) {
        for (int y = cy-radius; y <= cy+radius; y++)
            for (int x = cx-radius; x <= cx+radius; x++) {
                int distance = Math.abs(cx-x) + Math.abs(cy-y);
                if (distance >= radius-4 && distance <= radius)
                    ink(x, y, OmrMeasurePostProcessor.SYMBOL);
            }
    }

    private void ellipse(int cx, int cy) {
        for (int y = cy-6; y <= cy+6; y++)
            for (int x = cx-10; x <= cx+10; x++)
                if (Math.pow((x-cx)/10d, 2) + Math.pow((y-cy)/6d, 2) <= 1)
                    ink(x, y, OmrMeasurePostProcessor.NOTEHEAD);
    }

    private void rect(int x0, int y0, int x1, int y1, byte label) {
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) ink(x,y,label);
    }

    private void ink(int x, int y, byte label) {
        gray[y * WIDTH + x] = 0;
        labels[y * WIDTH + x] = label;
    }
}
