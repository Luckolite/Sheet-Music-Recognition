// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ConfirmedFlatNaturalRecoveryTest {
    private static final int WIDTH = 360, HEIGHT = 240, NOTE_X = 204, NOTE_Y = 120;
    private static final float GAP = 16;
    private final byte[] labels = new byte[WIDTH * HEIGHT];
    private final byte[] ink = new byte[labels.length];

    @Test
    public void aCompleteOwnedFlatCannotSeedItsOwnReplacementNatural() throws Exception {
        flat();
        var query = query();
        int original = originalEdge(query);
        assertTrue(original >= 0);
        assertEquals(original + 1, newEdge(query, ink));
    }

    @Test
    public void anErasedBowlDoesNotSuppressTheExistingNaturalRecoveryPath() throws Exception {
        flat();
        var query = query();
        var erased = ink.clone();
        fill(erased, NOTE_X - 22, NOTE_Y - 12, NOTE_X - 10, NOTE_Y + 9, 255);
        assertEquals(originalEdge(query), newEdge(query, erased));
    }

    private void flat() {
        Arrays.fill(ink, (byte)255);
        int x = NOTE_X - 26, y = NOTE_Y;
        mark(x, y - 27, x + 2, y + 10);
        mark(x, y - 9, x + 12, y - 6);
        mark(x + 10, y - 8, x + 13, y);
        for (int d = 0; d <= 9; d++) {
            int at = x + 11 - d;
            mark(at - 1, y + d, at + 1, y + d + 1);
        }
    }

    private record Query(Class<?> component, Object head, List<?> choices) {}

    private Query query() throws Exception {
        var type = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var shape = type.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        shape.setAccessible(true);
        var head = shape.newInstance(120, NOTE_X - 9, NOTE_X + 9, NOTE_Y - 6, NOTE_Y + 6,
                (float) NOTE_X, (float) NOTE_Y);
        int minX = WIDTH, maxX = -1, minY = HEIGHT, maxY = -1, sumX = 0, sumY = 0, count = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != OmrMeasurePostProcessor.CLEF_OR_KEY) continue;
            int x = i % WIDTH, y = i / WIDTH;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            sumX += x;
            sumY += y;
            count++;
        }
        var glyph = shape.newInstance(count, minX, maxX, minY, maxY, sumX / (float) count, sumY / (float) count);
        var choice = Class.forName(OmrScoreInterpreter.class.getName() + "$AccidentalCandidate")
                .getDeclaredConstructor(type, byte.class);
        choice.setAccessible(true);
        return new Query(type, head, List.of(choice.newInstance(glyph, OmrMeasurePostProcessor.CLEF_OR_KEY)));
    }

    private int originalEdge(Query q) throws Exception {
        var edge = OmrScoreInterpreter.class.getDeclaredMethod("nearestFlatRightEdge",
                byte[].class, int.class, int.class, List.class, q.component, float.class);
        edge.setAccessible(true);
        return (Integer) edge.invoke(null, labels, WIDTH, HEIGHT, q.choices, q.head, GAP);
    }

    private int newEdge(Query q, byte[] source) throws Exception {
        var edge = OmrScoreInterpreter.class.getDeclaredMethod("naturalRecoveryEdge",
                byte[].class, byte[].class, byte[].class, int.class, int.class, List.class, q.component, float.class);
        edge.setAccessible(true);
        return (Integer) edge.invoke(null, labels, ink, source, WIDTH, HEIGHT, q.choices, q.head, GAP);
    }

    private void mark(int left, int top, int right, int bottom) {
        fill(ink, left, top, right, bottom, 0);
        fill(labels, left, top, right, bottom, OmrMeasurePostProcessor.CLEF_OR_KEY);
    }

    private static void fill(byte[] data, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(data, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
