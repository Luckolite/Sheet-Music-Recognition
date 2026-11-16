// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class PrintedDottedTripletDetachedDotTest {
    private static final int WIDTH = 160, HEIGHT = 160;

    @Test
    public void aDotCoreOnTheHeadShoulderCannotCountAsATripletDot() throws Exception {
        byte[] detached = new byte[WIDTH * HEIGHT];
        Arrays.fill(detached, (byte) 255);
        for (int y = 75; y <= 85; y++) {
            Arrays.fill(detached, y * WIDTH + 51, y * WIDTH + 68, (byte) 0);
        }
        for (int y = 77; y <= 83; y++) {
            Arrays.fill(detached, y * WIDTH + 78, y * WIDTH + 84, (byte) 0);
        }
        byte[] attached = detached.clone();
        Arrays.fill(attached, 80 * WIDTH + 68, 80 * WIDTH + 80, (byte) 0);
        assertNotNull(WrappedTripletDotInk.uniqueCore(attached, WIDTH, HEIGHT, 60, 80, 110, 16));
        assertTrue(isDetachedDot(detached));
        assertFalse(isDetachedDot(attached));
    }

    @Test
    public void fourDottedTripletsTakeOneBeatEachOnlyWithTwoCompleteOpticalPlanes() {
        int width = 760, height = 290, gap = 16;
        int left = 100, right = 588, top = 150, bottom = top + 4 * gap;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int row = top; row <= bottom; row += gap) fill(gray, width, 72, row, 632, row);
        fill(gray, width, left, top, left + 1, bottom);
        fill(gray, width, right, top, right + 1, bottom);
        var notes = new java.util.ArrayList<ScoreNoteEvent>();
        for (int group = 0; group < 4; group++) {
            int first = 126 + group * 122;
            int[] columns = {first, first + 48, first + 82};
            int rail = 138;
            for (int slot = 0; slot < 3; slot++) {
                int x = columns[slot], headY = bottom - slot * gap;
                fill(gray, width, x - 9, headY - 5, x + 9, headY + 5);
                fill(gray, width, x + 7, rail, x + 9, headY);
                notes.add(new ScoreNoteEvent(0, (x - left) / (float)(right - left), 2 * slot, 0, 1,
                        headY / (float)height, false, slot == 0 ? 1 : 0, slot == 1 ? 2 : 1,
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0).withClef(ScoreNoteEvent.CLEF_TREBLE).withStemDirection(1));
            }
            fill(gray, width, columns[0] + 7, rail, columns[2] + 9, rail + 5);
            fill(gray, width, columns[1] - 5, rail + 12, columns[1] + 9, rail + 16);
            for (int y = 205; y <= 211; y++)
                for (int x = first + 17; x <= first + 23; x++)
                    if ((x - first - 20) * (x - first - 20) + (y - 208) * (y - 208) <= 10)
                        gray[y * width + x] = 0;
        }
        var meter = new ScoreMeterChange(0, 4, 4);
        var score = new ScorePageInterpretation(java.util.List.of(
                new MeasureRegion(left / (float)width, right / (float)width, 116f / height, 248f / height)),
                notes, 0, java.util.List.of(), java.util.List.of(), java.util.List.of(meter));
        var candidate = PrintedDottedTripletBar.apply(score, meter, gray, gray, width, height);
        for (int i = 0; i < 12; i++) {
            var n = candidate.notes().get(i);
            org.junit.Assert.assertEquals(3, n.tupletDivisor());
            var placed = ScoreNoteTiming.writtenPlacement(n, candidate.notes(), 4f).orElseThrow();
            org.junit.Assert.assertEquals(i / 3 + (i % 3 == 0 ? 0 : i % 3 == 1 ? .5f : 2f / 3),
                    placed.onsetBeats(), .0001f);
        }
        byte[] noDot = gray.clone();
        for (int row = 203; row <= 213; row++)
            Arrays.fill(noDot, row * width + 140, row * width + 155, (byte)255);
        org.junit.Assert.assertSame(score, PrintedDottedTripletBar.apply(score, meter, gray, noDot, width, height));
        org.junit.Assert.assertSame(score, PrintedDottedTripletBar.apply(score, meter, noDot, gray, width, height));
    }

    private static void fill(byte[] gray, int width, int l, int t, int r, int b) {
        for (int y = t; y <= b; y++) Arrays.fill(gray, y * width + l, y * width + r + 1, (byte)0);
    }

    private static boolean isDetachedDot(byte[] gray) throws Exception {
        Class<?> owner = PrintedDottedTripletBar.class;
        var raster = Class.forName(owner.getName() + "$Raster");
        var head = Class.forName(owner.getName() + "$Head");
        var rasterData = raster.getDeclaredConstructor(byte[].class, int.class, int.class);
        var headData =
                head.getDeclaredConstructor(
                        int.class, ScoreNoteEvent.class, float.class, float.class);
        var check = owner.getDeclaredMethod("detachedDot", raster, head, float.class, float.class);
        rasterData.setAccessible(true);
        headData.setAccessible(true);
        check.setAccessible(true);
        return (boolean)
                check.invoke(
                        null,
                        rasterData.newInstance(gray, WIDTH, HEIGHT),
                        headData.newInstance(0, null, 60f, 80f),
                        110f,
                        16f);
    }
}
