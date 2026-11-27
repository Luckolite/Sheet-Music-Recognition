// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Independently constructed oversized numerals and notehead controls. */
public class TallOpeningFourFragmentTest {
    private static final int WIDTH = 200, HEIGHT = 190;
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    private void ink(int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) 30);
    }

    private void staff() {
        Arrays.fill(gray, (byte) 250);
        for (int y = 60; y <= 132; y += 18) ink(8, y, 190, y);
    }

    private void digit(int top) {
        for (int y = top - 32; y <= top + 5; y++) {
            int left = 57 - (y - (top - 32)) * 26 / 37;
            ink(left, y, left + 5, y);
        }
        ink(63, top - 17, 67, top + 16);
        ink(36, top + 3, 81, top + 8);
    }

    private Object head(int x, int y) throws ReflectiveOperationException {
        var type = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var constructor = type.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        constructor.setAccessible(true);
        return constructor.newInstance(75, x - 6, x + 6, y - 4, y + 4, (float) x, (float) y);
    }

    private List<?> fragments(List<?> heads, List<MeasureRegion> measures)
            throws ReflectiveOperationException {
        var staff = Class.forName(OmrScoreInterpreter.class.getName() + "$Staff");
        var constructor = staff.getDeclaredConstructor(float.class, float.class, float.class);
        constructor.setAccessible(true);
        var method = OmrScoreInterpreter.class.getDeclaredMethod("tallOpeningFourFragments",
                byte[].class, int.class, int.class, List.class, List.class, List.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(null, gray, WIDTH, HEIGHT, heads,
                List.of(constructor.newInstance(60f, 132f, 18f)), measures);
    }

    @Test
    public void matchingOversizedFoursOwnBothFragments() throws Exception {
        staff();
        digit(60);
        digit(132);
        Object upper = head(70, 64), lower = head(70, 136);
        assertEquals(List.of(upper, lower), fragments(List.of(upper, lower, head(125, 132)),
                List.of(new MeasureRegion(.32f, .96f, .23f, .83f))));
    }

    @Test
    public void aSingleFourDoesNotOwnAnAlignedNote() throws Exception {
        staff();
        digit(60);
        ink(63, 130, 76, 139);
        assertEquals(List.of(), fragments(List.of(head(70, 64), head(70, 136), head(125, 132)),
                List.of(new MeasureRegion(.32f, .96f, .23f, .83f))));
    }

    @Test
    public void twoFilledHeadsWithAnOctaveStemArePreserved() throws Exception {
        staff();
        ink(63, 60, 76, 68);
        ink(63, 132, 76, 140);
        ink(76, 41, 78, 140);
        assertEquals(List.of(), fragments(List.of(head(70, 64), head(70, 136), head(125, 132)),
                List.of(new MeasureRegion(.32f, .96f, .23f, .83f))));
    }

    @Test
    public void identicalThickInkBeyondTheOpeningIsPreserved() throws Exception {
        staff();
        digit(60);
        digit(132);
        assertEquals(List.of(), fragments(List.of(head(70, 64), head(70, 136), head(125, 132)),
                List.of(new MeasureRegion(.04f, .32f, .23f, .83f),
                        new MeasureRegion(.32f, .96f, .23f, .83f))));
    }
}
