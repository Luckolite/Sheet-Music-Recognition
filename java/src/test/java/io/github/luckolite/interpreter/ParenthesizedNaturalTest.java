// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ParenthesizedNaturalTest {
    private static final int W = 90, H = 120, INK = OmrMeasurePostProcessor.CLEF_OR_KEY;

    @Test
    public void offsetSpinesAndTwoParenthesesRevealTheNatural() throws Exception {
        assertEquals(48f, pitch(true, true, false), 2.5f);
    }

    @Test
    public void evenSpineEndsOrMissingSideEvidenceDoNotProveANatural() throws Exception {
        assertTrue(Float.isNaN(pitch(false, true, false)));
        assertTrue(Float.isNaN(pitch(true, false, false)));
        assertTrue(Float.isNaN(pitch(true, true, true)));
    }

    private static float pitch(boolean natural, boolean complete, boolean missingInk) throws Exception {
        byte[] labels = new byte[W * H], gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        for (int y = 22; y <= (natural ? 61 : 74); y++) {
            for (int x = 19; x <= 21; x++) mark(labels, gray, x, y);
        }
        for (int y = (natural ? 35 : 22); y <= 74; y++) {
            for (int x = 29; x <= 31; x++) mark(labels, gray, x, y);
        }
        for (int x = 19; x <= 31; x++) {
            int rise = (31 - x) / 4;
            for (int y = 35; y < 40; y++) mark(labels, gray, x, y + rise);
            for (int y = 51; y < 56; y++) mark(labels, gray, x, y + rise);
        }
        for (int y = 30; y <= 66; y++) {
            int inset = Math.round(5 * (y - 48) * (y - 48) / 324f);
            mark(labels, gray, 13 + inset, y);
            mark(labels, gray, 14 + inset, y);
            if (complete) {
                mark(labels, gray, 36 - inset, y);
                mark(labels, gray, 37 - inset, y);
            }
        }
        if (missingInk) Arrays.fill(gray, (byte) 255);
        var decoder = OmrScoreInterpreter.class;
        var component = Class.forName(decoder.getName() + "$Component");
        var accidental = Class.forName(decoder.getName() + "$AccidentalCandidate");
        var bbox = component.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        bbox.setAccessible(true);
        var wrapper = accidental.getDeclaredConstructor(component, byte.class);
        wrapper.setAccessible(true);
        var enclosed = decoder.getDeclaredMethod("enclosedNaturalPitch",
                byte[].class, byte[].class, int.class, accidental, float.class);
        enclosed.setAccessible(true);
        Object glyph = wrapper.newInstance(bbox.newInstance(390, 13, 37, 22, 74, 25f, 48f), (byte) INK);
        return (float) enclosed.invoke(null, labels, gray, W, glyph, 17f);
    }

    private static void mark(byte[] labels, byte[] gray, int x, int y) {
        labels[y * W + x] = (byte) INK;
        gray[y * W + x] = 0;
    }
}
