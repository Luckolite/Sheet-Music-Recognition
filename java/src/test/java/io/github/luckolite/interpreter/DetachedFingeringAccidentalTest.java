// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class DetachedFingeringAccidentalTest {
    private static final int WIDTH = 264;
    private static final int HEIGHT = 224;

    @Test
    public void aDetachedOneNeedsItsPrecedingNoteAndTheOwnedRail() throws Exception {
        byte[] paper = paper();
        paint(paper, 70, 73, 72, 90, (byte) 0);
        paint(paper, 65, 72, 75, 78, (byte) 0);
        paint(paper, 66, 76, 88, 90, (byte) 0);
        assertTrue(owns(paper));

        byte[] cutRail = paper.clone();
        paint(cutRail, 84, 91, 159, 168, (byte) 255);
        assertFalse(owns(cutRail));
    }

    @Test
    public void compactFlatStillOwnsItsLowerBowl() throws Exception {
        byte[] paper = paper();
        paint(paper, 65, 68, 72, 90, (byte) 0);
        paint(paper, 68, 76, 81, 88, (byte) 0);
        assertFalse(owns(paper));
    }

    private static byte[] paper() {
        byte[] image = new byte[WIDTH * HEIGHT];
        Arrays.fill(image, (byte) 255);
        paint(image, 61, 79, 103, 112, (byte) 0);
        paint(image, 101, 119, 87, 96, (byte) 0);
        paint(image, 60, 62, 107, 164, (byte) 0);
        paint(image, 100, 102, 91, 164, (byte) 0);
        paint(image, 60, 102, 161, 165, (byte) 0);
        return image;
    }

    private static boolean owns(byte[] image) throws Exception {
        var decoder = OmrScoreInterpreter.class;
        var component = Class.forName(decoder.getName() + "$Component");
        var shape = component.getDeclaredConstructor(int.class, int.class, int.class,
                int.class, int.class, float.class, float.class);
        shape.setAccessible(true);
        var sign = shape.newInstance(102, 65, 76, 72, 90, 70f, 81f);
        var first = shape.newInstance(208, 61, 79, 101, 114, 70f, 108f);
        var second = shape.newInstance(208, 101, 119, 85, 98, 110f, 92f);
        var guard = decoder.getDeclaredMethod("detachedOneOverOwnedBeam",
                byte[].class, int.class, int.class, component, component, component, float.class);
        guard.setAccessible(true);
        return (boolean) guard.invoke(null, image, WIDTH, HEIGHT, sign, first, second, 16f);
    }

    private static void paint(byte[] image, int left, int right, int top, int bottom, byte shade) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(image, y * WIDTH + left, y * WIDTH + right + 1, shade);
    }
}
