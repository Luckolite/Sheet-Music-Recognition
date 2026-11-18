// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ClosedDyadsBeforeHalfTest {
    private static final int W = 560, H = 290, GAP = 16, LEFT = 60, RIGHT = 460, FLOOR = 215;
    private static final MeasureRegion BAR = new MeasureRegion(
            LEFT / (float) W, RIGHT / (float) W, 117f / H, 249f / H);
    private final byte[] ink = new byte[W * H];
    private final ScorePageInterpretation page;

    public ClosedDyadsBeforeHalfTest() {
        Arrays.fill(ink, (byte) 255);
        for (int line = 0; line < 5; line++) {
            int y = FLOOR - line * GAP;
            paint(LEFT - 5, RIGHT + 84, y, y + 1, 0);
        }
        paint(RIGHT + 4, RIGHT + 6, FLOOR - 4 * GAP, FLOOR, 0);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 9; i++) {
            int x = 82 + i * 39, upper = 145 - 6 * i, lower = upper + 56;
            notes.add(note(i, x, upper, 11));
            notes.add(note(i, x, lower, 4));
            for (int y : new int[] {upper, lower}) head(x, y, i == 8);
            int tip = i < 4 ? 248 - 5 * i : i < 8 ? 225 - 5 * (i - 4) : lower + 49;
            paint(x - 10, x - 8, upper, tip, 0);
        }
        rail(72, 189, 248);
        rail(228, 345, 225);
        page = new ScorePageInterpretation(List.of(BAR), notes);
    }

    @Test
    public void bothClosedGroupsAndHollowHeadsRestoreOnlyTheLowerHalf() {
        var corrected = apply(ink.clone(), 4);
        assertEquals(2f, corrected.notes().get(17).unbeamedDurationBeats(), 0f);
        assertEquals(page.notes().subList(0,17), corrected.notes().subList(0,17));
        for (int i = 0; i < 18; i++) {
            var n = corrected.notes().get(i);
            assertEquals(Math.min(2, (i / 2) * .25),
                    ScoreNoteTiming.beatInMeasure(n, corrected.notes(), 4), 1e-6);
        }
    }

    @Test
    public void requiresSourceRailHollowHeadClosingRuleAndFourQuarterCapacity() {
        var damaged = ink.clone();
        overwrite(ink, 386, 402, 149, 159, 0);
        assertSame(page, apply(damaged, 4));
    }

    @Test
    public void declinesAlteredRailOrMissingBoundaryEvenWhenNormalizedPlaneRetainsThem() {
        var altered = ink.clone();
        overwrite(altered, 140, 148, 220, 250, 255);
        assertSame(page, apply(altered, 4));
        altered = ink.clone();
        overwrite(altered, RIGHT + 2, RIGHT + 8, FLOOR - 4 * GAP, FLOOR, 255);
        assertSame(page, apply(altered, 4));
        assertSame(page, apply(ink.clone(), 3));
    }

    @Test
    public void twoPrintedFollowingDoubleSharpsDoNotLengthenEarlierSixteenths() {
        var notes = new ArrayList<>(page.notes());
        for (int index : new int[] {12, 13}) notes.set(index, value(notes.get(index), 2, 0));
        for (int index : new int[] {14, 15})
            notes.set(index, notes.get(index).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP));
        notes.set(17, value(notes.get(17), 0, 2));
        var dotted = new ScorePageInterpretation(List.of(BAR), notes);
        cross(335, 103);
        cross(335, 159);
        var repaired = ClosedDyadsBeforeHalf.apply(
                dotted, new ScoreMeterChange(0, 4, 4), ink, ink.clone(), W, H);
        assertEquals(0, repaired.notes().get(12).augmentationDots());
        assertEquals(0, repaired.notes().get(13).augmentationDots());
        assertEquals(notes.get(14), repaired.notes().get(14));
        assertEquals(notes.get(15), repaired.notes().get(15));
        var erased = ink.clone();
        overwrite(erased, 328, 342, 96, 110, 255);
        assertSame(dotted, ClosedDyadsBeforeHalf.apply(
                dotted, new ScoreMeterChange(0, 4, 4), ink, erased, W, H));
    }

    private void cross(int centerX, int centerY) {
        for (int dy = -7; dy <= 7; dy++) {
            for (int dx = -7; dx <= 7; dx++) {
                if (Math.abs(dx - dy) <= 2 || Math.abs(dx + dy) <= 2)
                    ink[(centerY + dy) * W + centerX + dx] = 0;
            }
        }
    }

    private static ScoreNoteEvent value(ScoreNoteEvent n, int dots, float beats) {
        return new ScoreNoteEvent(0, n.positionInMeasure(), n.staffStep(), 0, 1, n.pageY(),
                false, dots, n.beamCount(), n.writtenAccidental(), beats, 1).withStemDirection(-1);
    }

    private ScorePageInterpretation apply(byte[] otherPlane, int meter) {
        return ClosedDyadsBeforeHalf.apply(
                page, new ScoreMeterChange(0, meter, 4), ink, otherPlane, W, H);
    }

    private static ScoreNoteEvent note(int attack, int x, int y, int step) {
        float value = attack < 8 ? 0 : step == 11 ? 2 : 1;
        return new ScoreNoteEvent(0, (x - LEFT) / (float) (RIGHT - LEFT), step,
                0, 1, y / (float) H, false, 0, attack < 8 ? 2 : 0,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, value, 1).withStemDirection(-1);
    }

    private void head(int x, int y, boolean open) {
        for (int dy = -7; dy <= 7; dy++) {
            for (int dx = -10; dx <= 10; dx++) {
                if (dx * dx / 100f + dy * dy / 49f > 1) continue;
                if (open && dx * dx / 36f + dy * dy / 16f < 1) continue;
                ink[(y + dy) * W + x + dx] = 0;
            }
        }
    }

    private void rail(int left, int right, int tip) {
        for (int x = left; x <= right; x++) {
            int baseline = tip - Math.round(15 * (x - left) / (float) (right - left));
            paint(x, x, baseline - 6, baseline, 0);
            paint(x, x, baseline - 18, baseline - 12, 0);
        }
    }

    private void paint(int x0, int x1, int y0, int y1, int value) {
        overwrite(ink, x0, x1, y0, y1, value);
    }

    private static void overwrite(byte[] data, int x0, int x1, int y0, int y1, int value) {
        for (int y = y0; y <= y1; y++)
            Arrays.fill(data, y * W + x0, y * W + x1 + 1, (byte) value);
    }
}
