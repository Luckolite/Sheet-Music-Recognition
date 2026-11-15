// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedOctaveTripletBarsTest {
    private static final int WIDTH = 780, HEIGHT = 260;
    private static final int LEFT = 55, RIGHT = 652, TOP = 42, BOTTOM = 173;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final List<MeasureRegion> bars =
            List.of(
                    new MeasureRegion(
                            LEFT / (float) WIDTH,
                            RIGHT / (float) WIDTH,
                            TOP / (float) HEIGHT,
                            BOTTOM / (float) HEIGHT));
    private final List<ScoreNoteEvent> notes = new ArrayList<>();

    public ClosedOctaveTripletBarsTest() {
        Arrays.fill(gray, (byte) 255);
        for (int row = 80; row <= 144; row += 16) fill(LEFT, RIGHT, row, row + 1, 0);
        fill(LEFT, LEFT + 2, 80, 144, 0);
        fill(RIGHT - 1, RIGHT + 1, 80, 144, 0);
        for (int slot = 0; slot < 12; slot++) {
            int x = 90 + slot * 48, upper = 64 + (slot % 3) * 8, lower = upper + 56;
            int shaft = x - 10, tip = 195;
            fill(x - 9, x + 7, upper - 4, upper + 3, 0);
            fill(x - 9, x + 7, lower - 4, lower + 3, 0);
            fill(shaft - 1, shaft + 1, upper, tip, 0);
            if (slot % 3 == 0) fill(shaft, shaft + 97, tip - 6, tip, 0);
            int ratio = slot >= 6 && slot < 9 ? 3 : 1;
            notes.add(note(x, upper, (144 - upper) / 8, ratio));
            notes.add(note(x, lower, (144 - lower) / 8, ratio));
        }
    }

    @Test
    public void completesAllDyadsInAClosedBar() {
        var written = infer(4);
        assertEquals(notes.stream().map(n -> n.withTupletRatio(3, 2)).toList(), written);
    }

    @Test
    public void requiresCommonTimeAndIndependentClosingBarline() {
        assertSame(notes, infer(6));
        fill(RIGHT - 2, RIGHT + 2, 80, 144, 255);
        assertSame(notes, infer(4));
    }

    @Test
    public void rejectsBrokenOrBridgedTriplets() {
        fill(110, 128, 189, 196, 255);
        assertSame(notes, infer(4));
        fill(110, 128, 189, 195, 0);
        fill(176, 224, 189, 195, 0);
        assertSame(notes, infer(4));
    }

    @Test
    public void protectsAnIndependentDotOrUnownedDyadHead() {
        fill(107, 111, 62, 66, 0);
        assertSame(notes, infer(4));
        fill(107, 111, 62, 66, 255);
        fill(79, 81, 94, 105, 255);
        assertSame(notes, infer(4));
    }

    @Test
    public void admitsShiftedCentroidOnlyWithSharedShaftAndCompleteSeedGroup() {
        int slot = 9;
        notes.set(2 * slot, note(90 + slot * 48 - 3, 64, 10, 1));
        assertEquals(notes.stream().map(n -> n.withTupletRatio(3, 2)).toList(), infer(4));

        for (int owner = 12; owner < 20; owner++) {
            boolean seeded = owner >= 14;
            notes.set(owner, notes.get(owner).withTupletRatio(seeded ? 3 : 1, seeded ? 2 : 1));
        }
        assertSame(notes, infer(4));
    }

    @Test
    public void provesFourUnseededTripletsWithMixedShaftDirections() {
        fill(68, 215, 26, 199, 255);
        for (int row = 80; row <= 144; row += 16) fill(68, 215, row, row + 1, 0);
        for (int i = 0; i < notes.size(); i++) notes.set(i, notes.get(i).withTupletRatio(1, 1));
        for (int slot = 0; slot < 3; slot++) {
            int x = 90 + slot * 48, upper = 64 + slot * 8, lower = upper + 56;
            fill(x - 7, x + 10, upper - 4, upper + 3, 0);
            fill(x - 7, x + 10, lower - 4, lower + 3, 0);
            fill(x + 9, x + 11, 34, lower, 0);
            notes.set(slot * 2, notes.get(slot * 2).withStemDirection(1));
            notes.set(slot * 2 + 1, notes.get(slot * 2 + 1).withStemDirection(1));
        }
        fill(100, 197, 29, 35, 0);
        assertEquals(notes.stream().map(n -> n.withTupletRatio(3, 2)).toList(), infer(4));
        fill(116, 133, 29, 35, 255);
        assertSame(notes, infer(4));
    }

    private List<ScoreNoteEvent> infer(int numerator) {
        return ClosedOctaveTripletBars.apply(
                notes,
                List.of(),
                bars,
                List.of(new ScoreMeterChange(0, numerator, 4)),
                gray,
                WIDTH,
                HEIGHT);
    }

    private ScoreNoteEvent note(int x, int y, int staffStep, int ratio) {
        return new ScoreNoteEvent(
                0,
                (x - LEFT) / (float) (RIGHT - LEFT),
                staffStep,
                0,
                1,
                y / (float) HEIGHT,
                false,
                0,
                1,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                0,
                ratio,
                0,
                0,
                30,
                false,
                0,
                false,
                0,
                0,
                ratio == 3 ? 2 : 1,
                -1,
                ScoreNoteEvent.Kind.PITCHED,
                ScoreNoteEvent.VisualSize.FULL,
                0,
                null);
    }

    private void fill(int x0, int x1, int y0, int y1, int shade) {
        for (int y = y0; y <= y1; y++)
            Arrays.fill(gray, y * WIDTH + x0, y * WIDTH + x1 + 1, (byte) shade);
    }
}
