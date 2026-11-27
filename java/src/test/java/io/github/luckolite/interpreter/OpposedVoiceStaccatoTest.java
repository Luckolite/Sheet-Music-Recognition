// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OpposedVoiceStaccatoTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 224;

    @Test
    public void exteriorDotStaysOnTheDownStemVoice() {
        assertArrayEquals(new int[] {0, NoteArticulation.STACCATO}, marks(true));
    }

    @Test
    public void exteriorDotStillMarksAChordSharingOneShaft() {
        assertArrayEquals(new int[] {NoteArticulation.STACCATO, NoteArticulation.STACCATO}, marks(false));
    }

    private static int[] marks(boolean opposed) {
        byte[] gray = new byte[WIDTH * HEIGHT], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int y = opposed ? 129 : 104; y <= 154; y++) ink(gray, labels, 98, y, 1);
        if (opposed)
            for (int y = 72; y <= 104; y++) ink(gray, labels, 112, y, 1);
        for (int y = 157; y < 161; y++)
            for (int x = 103; x < 107; x++) ink(gray, labels, x, y, 0);
        return NoteArticulationDetector.detect(labels, gray, WIDTH, HEIGHT, List.of(
                new NoteArticulationDetector.Anchor(105, 104, 12, 0),
                new NoteArticulationDetector.Anchor(105, 129, 12, 0)));
    }

    private static void ink(byte[] gray, byte[] labels, int x, int y, int label) {
        gray[y * WIDTH + x] = 0;
        labels[y * WIDTH + x] = (byte) label;
    }
}
