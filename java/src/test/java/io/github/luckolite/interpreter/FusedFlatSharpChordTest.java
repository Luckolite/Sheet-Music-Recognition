// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public final class FusedFlatSharpChordTest {
    private static final int WIDTH = 155, HEIGHT = 130;
    private static final float GAP = 17;

    @Test
    public void rawShapesAndACommonStemProveSeparateChordAccidentals() throws Exception {
        assertArrayEquals(new int[] {-1, 1}, read(true, true));
    }

    @Test
    public void bothPrintedShapesAndTheCommonStemAreRequired() throws Exception {
        assertArrayEquals(new int[] {2, 2}, read(false, true));
        assertArrayEquals(new int[] {2, 2}, read(true, false));
    }

    private static int[] read(boolean sourceSharp, boolean commonShaft) throws Exception {
        byte[] labels = new byte[WIDTH * HEIGHT], gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        ink(gray, 30, 47, 32, 91);
        ink(gray, 30, 68, 43, 71);
        ink(gray, 42, 70, 44, 79);
        for (int d = 0; d < 12; d++) {
            int x = 43 - d;
            ink(gray, x - 1, 79 + d, x + 1, 80 + d);
        }
        ink(gray, 50, 49, 51, 91);
        ink(gray, 58, 47, 59, 89);
        for (int x = 47; x <= 62; x++) {
            int rise = (62 - x) / 5;
            ink(gray, x, 56 + rise, x, 60 + rise);
            ink(gray, x, 74 + rise, x, 78 + rise);
        }
        byte[] source = gray.clone();
        if (!sourceSharp)
            for (int y = 47; y <= 92; y++)
                Arrays.fill(source, y * WIDTH + 47, y * WIDTH + 65, (byte) 255);
        if (commonShaft)
            for (int y = 34; y <= 79; y++) labels[y * WIDTH + 89] = OmrMeasurePostProcessor.STEM_OR_REST;

        var decoder = OmrScoreInterpreter.class;
        var component = Class.forName(decoder.getName() + "$Component");
        var accidental = Class.forName(decoder.getName() + "$AccidentalCandidate");
        var box = component.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        var wrapper = accidental.getDeclaredConstructor(component, byte.class);
        box.setAccessible(true);
        wrapper.setAccessible(true);
        Object glyph = box.newInstance(585, 30, 64, 47, 92, 47f, 71f);
        Object lower = box.newInstance(320, 68, 89, 69, 87, 79f, 78.5f);
        Object upper = box.newInstance(320, 90, 111, 61, 79, 100f, 69.5f);
        var method = decoder.getDeclaredMethod("splitFlatSharpAtDisplacedChord",
                byte[].class, byte[].class, byte[].class, int.class, int.class, List.class,
                List.class, component, float.class);
        method.setAccessible(true);
        var candidates = List.of(wrapper.newInstance(glyph, OmrMeasurePostProcessor.CLEF_OR_KEY));
        var heads = List.of(lower, upper);
        return new int[] {
            (int) method.invoke(null, labels, gray, source, WIDTH, HEIGHT, candidates, heads, lower, GAP),
            (int) method.invoke(null, labels, gray, source, WIDTH, HEIGHT, candidates, heads, upper, GAP),
        };
    }

    private static void ink(byte[] data, int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(data, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
