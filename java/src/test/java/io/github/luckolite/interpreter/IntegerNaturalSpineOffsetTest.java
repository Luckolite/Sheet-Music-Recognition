// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
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

    @Test
    public void twoPrintedRulesCannotTurnOffsetNaturalSpinesIntoASharp() throws Exception {
        byte[] gray = new byte[SIZE * SIZE];
        byte[] mask = natural();
        Arrays.fill(gray, (byte) 255);
        for (int i = 0; i < mask.length; i++)
            if (mask[i] != 0) gray[i + 6] = 0;
        ink(gray, 1, 47, 18, 19, (byte) 0);
        ink(gray, 1, 47, 35, 36, (byte) 0);
        ink(gray, 1, 47, 51, 52, (byte) 0);
        assertTrue(throughRules(gray, gray));

        byte[] incomplete = gray.clone();
        ink(incomplete, 26, 30, 44, 55, (byte) 255);
        assertFalse(throughRules(gray, incomplete));

        byte[] sharp = gray.clone();
        ink(sharp, 27, 29, 10, 20, (byte) 0);
        ink(sharp, 17, 19, 49, 55, (byte) 0);
        assertFalse(throughRules(sharp, sharp));
    }

    private static boolean throughRules(byte[] gray, byte[] raw) throws Exception {
        var decoder = OmrScoreInterpreter.class;
        var component = Class.forName(decoder.getName() + "$Component");
        var constructor = component.getDeclaredConstructor(int.class, int.class, int.class,
                int.class, int.class, float.class, float.class);
        constructor.setAccessible(true);
        var glyph = constructor.newInstance(216, 16, 30, 10, 55, 23f, 34f);
        var head = constructor.newInstance(250, 35, 52, 27, 44, 43f, 36f);
        var proof = decoder.getDeclaredMethod("naturalBetweenContinuedRules",
                byte[].class, byte[].class, int.class, int.class, component, component, float.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, gray, raw, SIZE, SIZE, glyph, head, 15.875f);
    }

    @Test
    public void aNaturalCutFromAClusterRetainsItsLowerPitchOwner() throws Exception {
        byte[] gray = new byte[SIZE * SIZE];
        byte[] mask = natural();
        Arrays.fill(gray, (byte) 255);
        for (int i = 0; i < mask.length; i++)
            if (mask[i] != 0) gray[i + 6] = 0;
        ink(gray, 1, 47, 18, 19, (byte) 0);
        ink(gray, 1, 47, 35, 36, (byte) 0);
        ink(gray, 1, 47, 51, 52, (byte) 0);
        assertTrue(ownsLower(gray, false));
        assertFalse(ownsLower(gray, true));

        byte[] cut = gray.clone();
        ink(cut, 26, 30, 44, 55, (byte) 255);
        assertFalse(ownsLower(cut, false));
    }

    private static boolean ownsLower(byte[] gray, boolean reverse) throws Exception {
        var decoder = OmrScoreInterpreter.class;
        var component = Class.forName(decoder.getName() + "$Component");
        var constructor = component.getDeclaredConstructor(int.class, int.class, int.class,
                int.class, int.class, float.class, float.class);
        constructor.setAccessible(true);
        var glyph = constructor.newInstance(216, 16, 30, 10, 55, 23f, 34f);
        var lower = constructor.newInstance(250, 35, 52, 27, 44, 43f, 36f);
        var upper = constructor.newInstance(250, 35, 52, 11, 28, 43f, 20f);
        var proof = decoder.getDeclaredMethod("lowerNaturalInJoinedSigns", byte[].class,
                int.class, int.class, component, component, component, float.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, gray, SIZE, SIZE, glyph, reverse ? upper : lower,
                reverse ? lower : upper, 15.875f);
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
