// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Distinguishes complete optical natural spines at fractional staff spacing. */
public class IntegerNaturalSpineOffsetTest {
    private static final int SIZE = 64;
    private static final byte GLYPH = OmrMeasurePostProcessor.CLEF_OR_KEY;

    @Test
    public void completeSpinesCanEndAtAnIntegerRasterBound() throws Exception {
        assertTrue(isNatural(natural()));
    }

    @Test
    public void erasedLowerSpineDoesNotOwnAnAccidental() throws Exception {
        byte[] glyph = natural();
        ink(glyph, 20, 24, 47, 55, (byte) 0);
        assertFalse(isNatural(glyph));
    }

    @Test
    public void alignedSpinesDoNotBecomeANatural() throws Exception {
        byte[] glyph = natural();
        ink(glyph, 21, 23, 10, 20, GLYPH);
        ink(glyph, 11, 13, 49, 55, GLYPH);
        assertFalse(isNatural(glyph));
    }

    private static byte[] natural() {
        byte[] pixels = new byte[SIZE * SIZE];
        ink(pixels, 12, 12, 10, 48, GLYPH);
        ink(pixels, 11, 13, 12, 46, GLYPH);
        ink(pixels, 22, 22, 21, 55, GLYPH);
        ink(pixels, 21, 23, 23, 53, GLYPH);
        ink(pixels, 12, 22, 25, 29, GLYPH);
        ink(pixels, 12, 22, 44, 48, GLYPH);
        ink(pixels, 10, 11, 26, 27, GLYPH);
        ink(pixels, 23, 24, 26, 27, GLYPH);
        return pixels;
    }

    private static void ink(byte[] pixels, int left, int right, int top, int bottom, byte value) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) pixels[y * SIZE + x] = value;
        }
    }

    private static boolean isNatural(byte[] pixels) throws Exception {
        var decoder = OmrScoreInterpreter.class;
        var component = Class.forName(decoder.getName() + "$Component");
        var candidate = Class.forName(decoder.getName() + "$AccidentalCandidate");
        var constructor = candidate.getDeclaredConstructor(component, byte.class);
        constructor.setAccessible(true);
        var components = decoder.getDeclaredMethod("findComponents",
                byte[].class, int.class, int.class, byte.class);
        components.setAccessible(true);
        var shapes = (java.util.List<?>) components.invoke(null, pixels, SIZE, SIZE, GLYPH);
        var classify = decoder.getDeclaredMethod("isNaturalGlyph",
                byte[].class, int.class, int.class, candidate, float.class);
        classify.setAccessible(true);
        return (boolean) classify.invoke(null, pixels, SIZE, SIZE,
                constructor.newInstance(shapes.get(0), GLYPH), 15.875f);
    }
}
