// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedEighthTripletBarsTest {
    private static final int WIDTH = 500, HEIGHT = 230;
    private static final int LEFT = 40, RIGHT = 449, TOP = 46, BOTTOM = 177;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final List<MeasureRegion> bars = List.of(new MeasureRegion(
            LEFT / (float) WIDTH, RIGHT / (float) WIDTH, TOP / (float) HEIGHT, BOTTOM / (float) HEIGHT));
    private final List<ScoreNoteEvent> notes = new ArrayList<>();

    public ClosedEighthTripletBarsTest() {
        Arrays.fill(gray, (byte) 255);
        for (int y = 80; y <= 144; y += 16) fill(LEFT, RIGHT, y, y+1, 0);
        fill(LEFT, LEFT+2, 80, 144, 0);
        fill(RIGHT-1, RIGHT+1, 80, 144, 0);
        for (int i = 0; i < 12; i++) {
            int x = 67 + i * 32;
            int stem = i / 3 % 2 == 0 ? 1 : -1;
            int y = stem > 0 ? 112 + 8 * (i % 3) : 112 - 8 * (i % 3);
            int tip = stem > 0 ? 60 : 162;
            int shaft = x + stem * 9;
            fill(x-5, x+5, y-4, y+4, 0);
            fill(shaft-1, shaft+1, Math.min(tip, y), Math.max(tip, y), 0);
            if (i % 3 == 0) fill(shaft, shaft+65, tip-3, tip+3, 0);
            notes.add(new ScoreNoteEvent(0, (x-LEFT) / (float) (RIGHT-LEFT),
                    (144-y)/8, 0, 1, y / (float) HEIGHT, false, 0, 1, 2, 0, 1,
                    0, 0, 30, false, 0, false, 0, 0, 1, stem,
                    ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL, 0, null));
        }
    }

    @Test
    public void fourGroupsFillClosedCommonTimeBar() {
        var interpreted = infer(4);
        assertEquals(12, interpreted.stream().filter(n -> n.tupletDivisor() == 3
                && n.tupletNormalNotes() == 2).count());
    }

    @Test
    public void differentMeterAndOpenBarKeepOrdinaryRhythm() {
        assertSame(notes, infer(6));
        fill(RIGHT-2, RIGHT+1, 82, 142, 255);
        assertSame(notes, infer(4));
    }

    @Test
    public void missingBeamKeepsOrdinaryRhythm() {
        fill(109, 127, 55, 65, 255);
        assertSame(notes, infer(4));
    }

    private List<ScoreNoteEvent> infer(int numerator) {
        return WrappedEighthTriplets.closeCommonTimeBars(notes, List.of(), bars,
                List.of(new ScoreMeterChange(0, numerator, 4)), gray, WIDTH, HEIGHT);
    }

    private void fill(int x0, int x1, int y0, int y1, int value) {
        for (int y = y0; y <= y1; y++)
            Arrays.fill(gray, y*WIDTH+x0, y*WIDTH+x1+1, (byte) value);
    }
}
