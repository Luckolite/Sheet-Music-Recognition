// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import org.junit.Test;

public class CrossedOctaveReturnTest {
    @Test
    public void theDownwardDashAfterTheCrossingMustBePrinted() throws Exception {
        int width = 440, height = 160;
        byte[] ink = new byte[width * height];
        Arrays.fill(ink, (byte) 255);
        for (int x = 85; x <= 310; x += 9) paint(ink, width, x, x + 4, 42, 44, 0);
        paint(ink, width, 318, 321, 42, 46, 0);
        paint(ink, width, 318, 321, 51, 56, 0);
        paint(ink, width, 295, 343, 59, 64, 0);
        var incomplete = ink.clone();
        paint(ink, width, 318, 321, 69, 75, 0);
        assertTrue(returnEnd(ink, width, height, 58, 36, 82, 63) > 321);
        assertTrue(returnEnd(incomplete, width, height, 58, 36, 82, 63) < 0);
        paint(ink, width, 295, 343, 59, 64, 255);
        assertTrue(returnEnd(ink, width, height, 58, 36, 82, 63) < 0);
    }

    static float returnEnd(byte[] ink, int width, int height, int left, int top, int right, int bottom)
            throws Exception {
        var box = Class.forName(OctaveMarkDetector.class.getName() + "$InkBox");
        var constructor = box.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var method = OctaveMarkDetector.class.getDeclaredMethod(
                "octaveReturnAcrossSlur", byte[].class, int.class, int.class, box, float.class);
        method.setAccessible(true);
        return (float) method.invoke(null, ink, width, height, constructor.newInstance(left, top, right, bottom, 0), 16f);
    }

    private static void paint(byte[] ink, int width, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++) Arrays.fill(ink, y * width + left, y * width + right + 1, (byte) value);
    }
}
