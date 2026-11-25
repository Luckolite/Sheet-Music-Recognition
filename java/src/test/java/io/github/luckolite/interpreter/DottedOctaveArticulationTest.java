// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class DottedOctaveArticulationTest {
    private static final int WIDTH = 960, HEIGHT = 420, GAP = 20;
    private final byte[] gray = new byte[WIDTH * HEIGHT], labels = new byte[WIDTH * HEIGHT];

    public DottedOctaveArticulationTest() {
        Arrays.fill(gray, (byte) 255);
    }

    private void dot(int center, int y) {
        for (int row = y; row < y + 6; row++)
            Arrays.fill(gray, row * WIDTH + center - 3, row * WIDTH + center + 3, (byte) 0);
    }

    private NoteArticulationDetector.Anchor note(int x) {
        return new NoteArticulationDetector.Anchor(x, 200, GAP, 0);
    }

    private int[] detect(List<NoteArticulationDetector.Anchor> notes) {
        return NoteArticulationDetector.detect(labels, gray, WIDTH, HEIGHT, notes);
    }

    @Test
    public void denseOctaveDotsDoNotDecorateTheNotesBelow() {
        for (int i = 0; i < 13; i++) dot(300 + i * 10, 177);
        assertArrayEquals(new int[] {0, 0, 0}, detect(List.of(note(320), note(360), note(400))));
    }

    @Test
    public void independentlyPrintedStaccatosUnderTheDottedLineRemain() {
        for (int i = 0; i < 13; i++) dot(300 + i * 10, 166);
        dot(320, 183);
        dot(400, 183);
        assertArrayEquals(new int[] {NoteArticulation.STACCATO, 0, NoteArticulation.STACCATO},
                detect(List.of(note(320), note(360), note(400))));
    }

    @Test
    public void evenlySpacedRealStaccatosRemain() {
        var notes = new ArrayList<NoteArticulationDetector.Anchor>();
        for (int i = 0; i < 10; i++) {
            int x = 280 + i * 28;
            dot(x, 177);
            notes.add(note(x));
        }
        int[] expected = new int[notes.size()];
        Arrays.fill(expected, NoteArticulation.STACCATO);
        assertArrayEquals(expected, detect(notes));
    }
}
