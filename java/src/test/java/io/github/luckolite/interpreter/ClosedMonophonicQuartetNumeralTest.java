// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedMonophonicQuartetNumeralTest {
    private static final int WIDTH = 240;
    private static final int HEIGHT = 220;

    @Test
    public void centeredThreePreventsCorrectingThePrintedRhythm()
            throws ReflectiveOperationException {
        assertTrue(centeredThree(rasterWithThree(0)));
    }

    @Test
    public void aThreeOwnedByAnotherAttackDoesNotClaimTheTriplet()
            throws ReflectiveOperationException {
        assertFalse(centeredThree(rasterWithThree(38)));
    }

    @Test
    public void concludingSixteenthNeedsItsSecondPrintedRailInBothPlanes() {
        int width = 720, height = 280, left = 38, right = 670;
        var ink = new byte[width * height];
        Arrays.fill(ink, (byte) 255);
        for (int y = 96; y <= 160; y += 16) rectangle(ink, width, left, 708, y, y + 1, 0);
        rectangle(ink, width, left, left + 2, 96, 160, 0);
        rectangle(ink, width, right - 1, right + 1, 96, 160, 0);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 16; i++) {
            int x = 70 + i * 36;
            int y = 128 + 4 * (i % 4);
            int stem = x + 9;
            rectangle(ink, width, x - 6, x + 7, y - 4, y + 4, 0);
            rectangle(ink, width, stem - 1, stem + 1, 66, y, 0);
            if (i % 4 == 0) {
                rectangle(ink, width, stem, stem + 108, 64, 70, 0);
                rectangle(ink, width, stem, stem + 108, 77, 83, 0);
            }
            notes.add(new ScoreNoteEvent(1, (x - left) / (float) (right - left), (160 - y) / 8,
                    0, 1, y / (float) height, false, 0, i == 15 ? 1 : 2,
                    ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0f, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE,
                    false, 0, false, 0, 0, 1, 1,
                    ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL, 0, null));
        }
        var bars = List.of(new MeasureRegion(.2f, .7f, .02f, .1f),
                new MeasureRegion(left / (float) width, right / (float) width, 62f / height, 194f / height),
                new MeasureRegion((right + 12f) / width, .99f, 62f / height, 194f / height));
        var score = new ScorePageInterpretation(bars, notes, 0, List.of(), List.of(),
                List.of(new ScoreMeterChange(1, 4, 4), new ScoreMeterChange(2, 3, 4)),
                List.of(), List.of(), List.of(), List.of());
        var read = ClosedSixteenthDyads.applyClosedMonophonicThreeFour(
                score, ink, ink.clone(), width, height, 1);
        assertEquals(2, read.notes().get(15).beamCount());
        assertEquals(notes.subList(0, 15), read.notes().subList(0, 15));
        var cut = ink.clone();
        rectangle(cut, width, 589, 607, 77, 83, 255);
        assertSame(score, ClosedSixteenthDyads.applyClosedMonophonicThreeFour(
                score, ink, cut, width, height, 1));
        assertSame(score, ClosedSixteenthDyads.applyClosedMonophonicThreeFour(
                score, cut, ink, width, height, 1));
    }

    private static void rectangle(byte[] ink, int width, int x0, int x1, int y0, int y1, int value) {
        for (int y = y0; y <= y1; y++)
            Arrays.fill(ink, y * width + x0, y * width + x1 + 1, (byte) value);
    }

    private static boolean centeredThree(byte[] ink) throws ReflectiveOperationException {
        var method =
                ClosedSixteenthDyads.class.getDeclaredMethod(
                        "centralTripletNumeral",
                        ClosedSixteenthDyads.Ink.class,
                        float.class,
                        float.class,
                        float.class,
                        float.class,
                        UnnumberedTripletInk.Rows.class);
        method.setAccessible(true);
        return (boolean)
                method.invoke(
                        null,
                        new ClosedSixteenthDyads.Ink(ink, WIDTH, HEIGHT),
                        110f,
                        76f,
                        122f,
                        16f,
                        new UnnumberedTripletInk.Rows(0, HEIGHT - 1));
    }

    private static byte[] rasterWithThree(int horizontalOffset) {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        int[][] stroke = {
            {103, 132},
            {113, 132},
            {118, 135},
            {118, 137},
            {113, 141},
            {109, 141},
            {115, 142},
            {118, 145},
            {118, 147},
            {113, 151},
            {103, 151}
        };
        for (int i = 1; i < stroke.length; i++) {
            var a = stroke[i - 1];
            var b = stroke[i];
            int length = Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]));
            for (int sample = 0; sample <= length; sample++) {
                int x =
                        horizontalOffset
                                + Math.round(a[0] + (b[0] - a[0]) * sample / (float) length);
                int y = Math.round(a[1] + (b[1] - a[1]) * sample / (float) length);
                for (int row = y - 1; row <= y + 1; row++)
                    Arrays.fill(ink, row * WIDTH + x - 1, row * WIDTH + x + 2, (byte) 0);
            }
        }
        return ink;
    }
}
