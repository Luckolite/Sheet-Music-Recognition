// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DisplacedNaturalOwnerTest {
    private static final int WIDTH = 100, HEIGHT = 100;
    private static final float GAP = 17;

    @Test
    public void aSharedStemAssignsTheNaturalToTheAlignedHead() throws Exception {
        var result = ownership(true, true);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, result.lowerChoice());
        assertEquals(ScoreNoteEvent.ACCIDENTAL_NATURAL, result.upperChoice());
        assertTrue(result.blocksLowerRawFallback());
    }

    @Test
    public void anIndependentHeadDoesNotBlockFallback() throws Exception {
        var result = ownership(false, true);
        assertFalse(result.blocksLowerRawFallback());
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, result.upperChoice());
    }

    @Test
    public void semanticInkAloneCannotBlockFallback() throws Exception {
        assertFalse(ownership(true, false).blocksLowerRawFallback());
    }

    private record Owners(int lowerChoice, int upperChoice, boolean blocksLowerRawFallback) {}

    private static Owners ownership(boolean commonShaft, boolean printed) throws Exception {
        byte[] labels = new byte[WIDTH * HEIGHT], gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        byte ink = OmrMeasurePostProcessor.CLEF_OR_KEY;
        box(labels, gray, 19, 22, 21, 61, ink);
        box(labels, gray, 29, 35, 31, 74, ink);
        for (int x = 19; x <= 31; x++) {
            int rise = (31 - x) / 4;
            box(labels, gray, x, 35 + rise, x, 39 + rise, ink);
            box(labels, gray, x, 51 + rise, x, 55 + rise, ink);
        }
        if (commonShaft)
            box(labels, gray, 54, 8, 54, 57, OmrMeasurePostProcessor.STEM_OR_REST);
        if (!printed)
            for (int y = 22; y <= 74; y++) Arrays.fill(gray, y * WIDTH + 19, y * WIDTH + 32, (byte) 255);
        var decoder = OmrScoreInterpreter.class;
        var component = Class.forName(decoder.getName() + "$Component");
        var accidental = Class.forName(decoder.getName() + "$AccidentalCandidate");
        Constructor<?> rect = component.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        rect.setAccessible(true);
        Object lower = rect.newInstance(260, 35, 54, 47, 66, 44.5f, 56f);
        Object upper = rect.newInstance(260, 55, 75, 39, 57, 65f, 48f);
        var heads = List.of(lower, upper);
        Constructor<?> wrapper = accidental.getDeclaredConstructor(component, byte.class);
        wrapper.setAccessible(true);
        var glyph = rect.newInstance(320, 19, 31, 22, 74, 25f, 48f);
        var candidates = List.of(wrapper.newInstance(glyph, ink));
        var choose = decoder.getDeclaredMethod("chooseWrittenAccidental",
                byte[].class, int.class, int.class, List.class, component, float.class, List.class, byte[].class);
        var fallback = decoder.getDeclaredMethod("naturalOwnedByDisplacedHead",
                byte[].class, byte[].class, int.class, int.class, List.class, component, List.class, float.class);
        choose.setAccessible(true);
        fallback.setAccessible(true);
        var a = choose.invoke(null, labels, WIDTH, HEIGHT, candidates, lower, GAP, heads, gray);
        var b = choose.invoke(null, labels, WIDTH, HEIGHT, candidates, upper, GAP, heads, gray);
        var value = a.getClass().getDeclaredMethod("accidental");
        value.setAccessible(true);
        return new Owners((int) value.invoke(a), (int) value.invoke(b),
                (boolean) fallback.invoke(null, labels, gray, WIDTH, HEIGHT, candidates, lower, heads, GAP));
    }

    private static void box(byte[] labels, byte[] gray, int left, int top, int right, int bottom, byte ink) {
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) {
                labels[y * WIDTH + x] = ink;
                gray[y * WIDTH + x] = 0;
            }
    }
}
