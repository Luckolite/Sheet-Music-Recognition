// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class TaperedFlatCounterTest {
    private static final int WIDTH = 160, HEIGHT = 170, LEFT = 89, TOP = 110, SPAN = 20;
    private final byte[] ink = new byte[WIDTH * HEIGHT];
    private final boolean[] glyph = new boolean[SPAN * 40];

    public TaperedFlatCounterTest() {
        Arrays.fill(ink, (byte) 255);
        for (int y = 111; y <= 140; y++) ink(93, 97, y);
        for (int y = 122; y <= 124; y++) ink(97, 106, y);
        for (int y = 125; y <= 133; y++) ink(104, 106, y);
        for (int y = 134; y <= 139; y++) ink(97, 106 - (y - 134), y);
    }

    @Test
    public void paperCounterAndCurvedLowerEdgeBelongToTheSamePrintedShape() throws Exception {
        assertTrue(proved(ink, glyph));
        var withoutBowlMembers = glyph.clone();
        for (int y = 125; y <= 133; y++)
            for (int x = 103; x <= 106; x++) withoutBowlMembers[(y - TOP) * SPAN + x - LEFT] = false;
        assertFalse(proved(ink, withoutBowlMembers));
        var filled = ink.clone();
        for (int y = 125; y <= 133; y++) Arrays.fill(filled, y * WIDTH + 97, y * WIDTH + 106, (byte) 0);
        assertFalse(proved(filled, glyph));
        var straight = ink.clone();
        var straightMembers = glyph.clone();
        for (int y = 134; y <= 140; y++) {
            straight[y * WIDTH + 107] = 0;
            straightMembers[(y - TOP) * SPAN + 107 - LEFT] = true;
        }
        assertFalse(proved(straight, straightMembers));
    }

    @Test
    public void overlappingUpperInkDoesNotFillTheLowerFlatsLocalPaper() throws Exception {
        for (int y = 111; y <= 121; y++) ink(104, 106, y);
        assertFalse(proved(ink, glyph));
        assertTrue(provedCompact(ink, glyph, 100));
        var filledCounter = ink.clone();
        for (int y = 125; y <= 132; y++)
            Arrays.fill(filledCounter, y * WIDTH + 99, y * WIDTH + 104, (byte) 0);
        assertFalse(provedCompact(filledCounter, glyph, 100));
    }

    private static boolean provedCompact(byte[] ink, boolean[] glyph, int clearUntil)
            throws ReflectiveOperationException {
        var detect = OmrScoreInterpreter.class.getDeclaredMethod(
                "flatSpineCounterAndTaper", byte[].class, int.class, float.class, float.class,
                int.class, int.class, boolean[].class, int.class, int.class, int.class, int.class, int.class);
        detect.setAccessible(true);
        return (boolean) detect.invoke(null, ink, WIDTH, 130f, 16f, 94, 107, glyph, SPAN, LEFT, TOP,
                clearUntil, 2);
    }

    private void ink(int left, int right, int y) {
        for (int x = left; x <= right; x++) {
            ink[y * WIDTH + x] = 0;
            glyph[(y - TOP) * SPAN + x - LEFT] = true;
        }
    }

    private static boolean proved(byte[] ink, boolean[] glyph) throws ReflectiveOperationException {
        var detect = OmrScoreInterpreter.class.getDeclaredMethod(
                "flatSpineCounterAndTaper", byte[].class, int.class, float.class, float.class,
                int.class, int.class, boolean[].class, int.class, int.class, int.class);
        detect.setAccessible(true);
        return (boolean) detect.invoke(null, ink, WIDTH, 130f, 16f, 94, 107, glyph, SPAN, LEFT, TOP);
    }
}
