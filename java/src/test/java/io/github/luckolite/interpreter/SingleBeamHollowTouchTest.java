// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class SingleBeamHollowTouchTest {
    private static final int WIDTH = 200, HEIGHT = 200, X = 100, STOP_Y = 122, GAP = 16;
    private static final int TOUCH_Y = 98, RADIUS = 11;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final byte[] labels = new byte[gray.length];

    @Test
    public void shortHollowOvalDoesNotTransposeAStoppedEighth() {
        draw(true, false);
        assertEquals(0, soundingOctaves());
    }

    @Test
    public void continuousStemAndOpenTouchPreserveTheHarmonic() {
        draw(false, false);
        assertEquals(2, soundingOctaves());
    }

    @Test
    public void filledTouchDoesNotTransposeAStoppedEighth() {
        draw(false, true);
        assertEquals(0, soundingOctaves());
    }

    private int soundingOctaves() {
        var note = new ScoreNoteEvent(0, X / (float) WIDTH, 0, 0, 1, STOP_Y / (float) HEIGHT,
                false, 0, 1, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 0, 0, 0,
                ScoreNoteEvent.CLEF_TREBLE).withStemDirection(1);
        return ArtificialHarmonics.apply(labels, gray, WIDTH, HEIGHT,
                        List.of(new MeasureRegion(0, 1, 0, 1)), List.of(note),
                        List.of(new PlayingTechniqueDetector.Staff(72, 136, GAP, 0, 1)))
                .get(0).octaveShift();
    }

    private void draw(boolean oval, boolean filled) {
        Arrays.fill(gray, (byte) 255);
        for (int y = TOUCH_Y - RADIUS; y <= TOUCH_Y + RADIUS; y++) {
            for (int x = X - RADIUS; x <= X + RADIUS; x++) {
                int u = x - X, v = y - TOUCH_Y;
                double distance = touchDistance(u, v, oval);
                if (distance <= RADIUS && (filled || distance >= RADIUS - 4))
                    gray[y * WIDTH + x] = 0;
            }
        }
        for (int y = STOP_Y - 6; y <= STOP_Y + 6; y++) {
            for (int x = X - 10; x <= X + 10; x++) {
                double u = (x - X) / 10.0, v = (y - STOP_Y) / 6.0;
                if (u * u + v * v <= 1) gray[y * WIDTH + x] = 0;
            }
        }
        for (int y = STOP_Y - 43; y <= STOP_Y; y++)
            for (int x = X + 9; x <= X + 11; x++) gray[y * WIDTH + x] = 0;
        for (int y = TOUCH_Y; y < TOUCH_Y + 3; y++)
            for (int x = X - 30; x <= X + 30; x++) gray[y * WIDTH + x] = 0;
        for (int y = TOUCH_Y - RADIUS; y <= TOUCH_Y + RADIUS; y++) {
            for (int x = X - RADIUS; x <= X + RADIUS; x++) {
                int index = y * WIDTH + x;
                if (gray[index] == 0 && touchDistance(x - X, y - TOUCH_Y, oval) <= RADIUS)
                    labels[index] = OmrMeasurePostProcessor.SYMBOL;
            }
        }
    }

    private static double touchDistance(int x, int y, boolean oval) {
        return oval ? Math.sqrt(x * x + y * y * 2.9) : Math.abs(x) + Math.abs(y);
    }
}
