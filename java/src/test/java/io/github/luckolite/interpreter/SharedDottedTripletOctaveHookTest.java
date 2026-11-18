// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class SharedDottedTripletOctaveHookTest {
    private static final int WIDTH = 760;
    private static final int HEIGHT = 290;
    private static final int GAP = 16;

    @Test
    public void twoHeadsUseOneOpticallyOwnedHookInBothRasters() {
        int left = 100, right = 604, top = 150, bottom = top + 4 * GAP;
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int row = top; row <= bottom; row += GAP) fill(gray, 72, row, 642, row);
        fill(gray, left, top, left + 2, bottom);
        fill(gray, right, top, right + 2, bottom);
        var notes = new ArrayList<ScoreNoteEvent>();
        int suspect = 3;
        for (int group = 0; group < 4; group++) {
            int x0 = 126 + group * 122, rail = 94;
            int[] columns = {x0, x0 + 48, x0 + 82};
            for (int slot = 0; slot < 3; slot++) {
                int column = columns[slot], lower = bottom - slot * GAP;
                fill(gray, column + 7, rail, column + 9, lower + 2);
                for (int octave = 0; octave < 2; octave++) {
                    int y = lower - octave * (7 * GAP / 2);
                    fill(gray, column - 9, y - 4, column + 9, y + 4);
                    int dots = slot == 0 ? 1 : 0;
                    int beams = slot == 1 && notes.size() != suspect ? 2 : 1;
                    notes.add(new ScoreNoteEvent(0, (column - left) / (float) (right - left),
                            2 * slot + octave * 7, 0, 1, y / (float) HEIGHT, false, dots, beams,
                            ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0)
                            .withClef(ScoreNoteEvent.CLEF_TREBLE).withStemDirection(1));
                    if (dots == 1) {
                        int center = y - 6;
                        for (int sy = center - 3; sy <= center + 3; sy++) {
                            for (int sx = column + 17; sx <= column + 23; sx++) {
                                int dx = sx - column - 20, dy = sy - center;
                                if (dx * dx + dy * dy <= 10) gray[sy * WIDTH + sx] = 0;
                            }
                        }
                    }
                }
            }
            fill(gray, columns[0] + 7, rail, columns[2] + 9, rail + 6);
            fill(gray, columns[1] - 5, rail + 13, columns[1] + 9, rail + 18);
        }
        var score = new ScorePageInterpretation(List.of(
                new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH, 116f / HEIGHT, 248f / HEIGHT)),
                notes, 0, List.of(), List.of(), List.of());
        var recovered = PrintedDottedTripletBar.apply(score, null, gray, gray, WIDTH, HEIGHT);
        assertEquals(2, recovered.notes().get(suspect).beamCount());
        for (int i = 0; i < 24; i++) {
            var note = recovered.notes().get(i);
            int attack = i / 2;
            var placement = ScoreNoteTiming.writtenPlacement(note, recovered.notes(), 4f).orElseThrow();
            assertEquals(attack / 3 + (attack % 3 == 0 ? 0 : attack % 3 == 1 ? .5f : 2f / 3),
                    placement.onsetBeats(), .0001f);
        }
        byte[] withoutHook = gray.clone();
        for (int y = 106; y <= 114; y++)
            Arrays.fill(withoutHook, y * WIDTH + 166, y * WIDTH + 181, (byte) 255);
        assertSame(score, PrintedDottedTripletBar.apply(score, null, gray, withoutHook, WIDTH, HEIGHT));
        assertSame(score, PrintedDottedTripletBar.apply(score, null, withoutHook, gray, WIDTH, HEIGHT));
    }

    private static void fill(byte[] plane, int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(plane, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
