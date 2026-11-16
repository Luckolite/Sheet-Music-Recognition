// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class InlineTripleBeforeSystemBreakTest {
    private static final int WIDTH = 1050, HEIGHT = 330;
    private static final int TOP = 90, GAP = 16, BOTTOM = TOP + 4 * GAP;
    private static final MeasureRegion PRIOR = region(120, 269);
    private static final MeasureRegion HALF = region(285, 370);
    private static final MeasureRegion RUN = region(435, 905);
    private static final MeterChangeDetector.Crop METER =
            new MeterChangeDetector.Crop(400, 86, 436, 159, TOP, GAP);

    @Test
    public void readsStackedMeterOnlyWithTwoIndependentStrokesInBothPlanes() throws Exception {
        var positive = staff(true);
        var absentRule = staff(false);
        var ordinaryNumerals = staff(true, "4/4");
        assertEquals(METER, crop(positive, positive));
        assertNull(crop(positive, absentRule));
        assertNull(crop(absentRule, positive));
        assertNull(crop(ordinaryNumerals, ordinaryNumerals));
    }

    @Test
    public void readsCompactTripleOnlyWhenBothRuleStrokesArePresentOnBothPlanes() throws Exception {
        var before = new MeterChangeDetector.Crop(384, 86, 436, 159, TOP, GAP);
        var half = region(285, 379);
        var run = region(397, 905);
        var positive = staff(true);
        for (int y = TOP; y <= BOTTOM; y++) {
            if ((y - TOP) % GAP == 0) continue;
            Arrays.fill(positive, y * WIDTH + 381, y * WIDTH + 393, (byte) 255);
        }
        stroke(positive, 386);
        stroke(positive, 394);
        var missing = positive.clone();
        for (int y = TOP; y <= BOTTOM; y++)
            if ((y - TOP) % GAP != 0)
                Arrays.fill(missing, y * WIDTH + 394, y * WIDTH + 396, (byte) 255);
        var wrongDigits = staff(true, "4/4");
        for (int y = before.top(); y < before.bottom(); y++)
            System.arraycopy(positive, y * WIDTH + 380, wrongDigits, y * WIDTH + 380, 20);
        var cropped = HalfDyadBeforeSixteenthBar.compactInlineTripleCrop(
                List.of(before), PRIOR, half, run, positive, positive, WIDTH, HEIGHT, GAP);
        assertEquals(new MeterChangeDetector.Crop(396, 86, 436, 159, TOP, GAP), cropped);
        assertNull(HalfDyadBeforeSixteenthBar.compactInlineTripleCrop(
                List.of(before), PRIOR, half, run, missing, positive, WIDTH, HEIGHT, GAP));
        assertNull(HalfDyadBeforeSixteenthBar.compactInlineTripleCrop(
                List.of(before), PRIOR, half, run, positive, missing, WIDTH, HEIGHT, GAP));
        assertNull(HalfDyadBeforeSixteenthBar.compactInlineTripleCrop(
                List.of(before), PRIOR, half, run, wrongDigits, positive, WIDTH, HEIGHT, GAP));
    }

    @Test
    public void requiresCompleteOctaveColumnsAndAllowsMatchedTerminalBeamClaim() {
        var gray = blankStaff(true);
        var notes = new ArrayList<ScoreNoteEvent>();
        notes.add(head(HALF, 0, 313, 7, 0, 2, 1, 0));
        notes.add(head(HALF, 0, 314, 0, 0, 2, 1, 0));
        for (int attack = 0; attack < 12; attack++) {
            int x = 455 + 35 * attack;
            int stem = attack < 8 ? 1 : -1;
            int depth = attack == 11 ? 3 : 2;
            notes.add(head(RUN, 1, x, 7, depth, 0, stem, 0));
            notes.add(head(RUN, 1, x + 1, 0, depth, 0, stem, 0));
        }
        assertTrue(qualifies(gray, notes));
        var dottedRun = new ArrayList<>(notes);
        dottedRun.set(2, head(RUN, 1, 455, 7, 2, 0, 1, 1));
        assertFalse(qualifies(gray, dottedRun));
        var beamConflict = new ArrayList<>(notes);
        beamConflict.set(notes.size() - 1, head(RUN, 1, 841, 0, 2, 0, -1, 0));
        assertFalse(qualifies(gray, beamConflict));
        notes.remove(notes.size() - 1);
        assertFalse(qualifies(gray, notes));
    }

    private static MeterChangeDetector.Crop crop(byte[] normal, byte[] raw) {
        return HalfDyadBeforeSixteenthBar.inlineTripleCrop(
                List.of(METER), PRIOR, HALF, RUN, normal, raw, WIDTH, HEIGHT, GAP);
    }

    private static boolean qualifies(byte[] gray, List<ScoreNoteEvent> notes) {
        return HalfDyadBeforeSixteenthBar.qualifiesStraightTriple(
                List.of(HALF, RUN),
                new OmrScoreInterpreter.Analysis(notes, List.of()),
                gray,
                WIDTH,
                HEIGHT);
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion(
                left / (float) WIDTH,
                right / (float) WIDTH,
                (TOP - 2.1f * GAP) / HEIGHT,
                (BOTTOM + 2.1f * GAP) / HEIGHT);
    }

    private static ScoreNoteEvent head(
            MeasureRegion region,
            int bar,
            int x,
            int step,
            int depth,
            float duration,
            int stem,
            int dots) {
        float position = (x / (float) WIDTH - region.left()) / (region.right() - region.left());
        float y = (BOTTOM - step * GAP * .5f) / HEIGHT;
        return new ScoreNoteEvent(
                bar,
                position,
                step,
                0,
                1,
                y,
                false,
                dots,
                depth,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                duration,
                1,
                0,
                0,
                30,
                false,
                0,
                false,
                0,
                0,
                1,
                stem,
                ScoreNoteEvent.Kind.PITCHED);
    }

    private static byte[] blankStaff(boolean secondStroke) {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += GAP)
            Arrays.fill(gray, y * WIDTH + 60, y * WIDTH + 1000, (byte) 0);
        stroke(gray, 277);
        stroke(gray, 381);
        if (secondStroke) stroke(gray, 391);
        return gray;
    }

    private static byte[] staff(boolean secondStroke) throws Exception {
        return staff(secondStroke, "3/4");
    }

    private static byte[] staff(boolean secondStroke, String meter) throws Exception {
        var gray = blankStaff(secondStroke);
        var glyphs = DarkMeterCoreTest.page(52, .6f, meter);
        for (int y = 0; y < METER.bottom() - METER.top(); y++) {
            // The vector fixture uses 14 px staff gaps. Align its staff with this fixture's 16 px.
            int glyphY = Math.round((METER.top() + y - TOP) * 14f / GAP + 8);
            if (glyphY < 0 || glyphY >= 72 || (glyphY - 8) % 14 == 0) continue;
            for (int x = 0; x < METER.right() - METER.left(); x++) {
                int ink = glyphs[glyphY * 80 + 22 + x] & 255;
                if (ink < 245) gray[(METER.top() + y) * WIDTH + METER.left() + x] = (byte) ink;
            }
        }
        return gray;
    }

    private static void stroke(byte[] gray, int first) {
        for (int y = TOP; y <= BOTTOM; y++)
            Arrays.fill(gray, y * WIDTH + first, y * WIDTH + first + 2, (byte) 0);
    }
}
