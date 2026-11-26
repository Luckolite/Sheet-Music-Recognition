// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class FusedNaturalPriorOwnershipTest {
    private static final int WIDTH = 240, HEIGHT = 190;
    private static final float GAP = 16;

    @Test
    public void earlierNaturalCannotRaiseTheNextStaffStep() throws Exception {
        byte[] original = natural();
        assertTrue(prove(original, original.clone()));
    }

    @Test
    public void eitherSourcePlaneMissingASpineWithholdsTheOwnershipChange() throws Exception {
        byte[] original = natural();
        byte[] broken = original.clone();
        for (int y = 70; y <= 129; y++) Arrays.fill(broken, y * WIDTH + 72, y * WIDTH + 78, (byte) 255);
        assertFalse(prove(original, broken));
        assertFalse(prove(broken, original));
    }

    private static byte[] natural() {
        byte[] raster = new byte[WIDTH * HEIGHT];
        Arrays.fill(raster, (byte) 255);
        rect(raster, 73, 75, 3, 37);
        rect(raster, 84, 86, 3, 39);
        for (int x = 73; x <= 86; x++) {
            int rise = (x - 73) / 4;
            rect(raster, x, 90 - rise, 1, 4);
            rect(raster, x, 107 - rise, 1, 4);
        }
        return raster;
    }

    private static void rect(byte[] raster, int x, int y, int width, int height) {
        for (int row = y; row < y + height; row++)
            Arrays.fill(raster, row * WIDTH + x, row * WIDTH + x + width, (byte) 0);
    }

    private static boolean prove(byte[] original, byte[] normalized) throws Exception {
        Class<?> interpreter = OmrScoreInterpreter.class;
        Class<?> component = Class.forName(interpreter.getName() + "$Component");
        var head = component.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class,
                float.class, float.class);
        head.setAccessible(true);
        var core = head.newInstance(300, 73, 87, 75, 124, 80f, 100f);
        var union = head.newInstance(320, 73, 96, 75, 124, 82f, 100f);
        var prior = head.newInstance(300, 90, 110, 91, 109, 100f, 100f);
        var next = head.newInstance(300, 117, 137, 83, 101, 127f, 92f);
        Class<?> candidate = Class.forName(interpreter.getName() + "$AccidentalCandidate");
        var glyph = candidate.getDeclaredConstructor(component, byte.class);
        glyph.setAccessible(true);
        var source = glyph.newInstance(core, OmrMeasurePostProcessor.CLEF_OR_KEY);
        var method = interpreter.getDeclaredMethod("priorHeadOwnsFusedNatural", byte[].class, byte[].class,
                int.class, int.class, component, component, List.class, List.class, float.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, normalized, original, WIDTH, HEIGHT, union, next,
                List.of(prior, next), List.of(source), GAP);
    }
}
