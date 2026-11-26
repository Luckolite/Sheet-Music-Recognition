// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HeaderInkOwnershipTest {
    @Test
    public void onlyIndependentAlignedCommonTimeCopiesOwnTheMarkedHeads() throws Exception {
        assertEquals(2, rejectedCommonHeads(true).size());
        assertTrue(rejectedCommonHeads(false).isEmpty());
    }

    @Test
    public void aRealFlatShaftIsRetainedEvenBesideTwoRoundDots() throws Exception {
        int width = 300, height = 200;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int y = 80; y <= 144; y += 16) rect(gray, width, 20, y, 260, 1);
        rect(gray, width, 100, 82, 3, 48);
        rect(gray, width, 100, 108, 19, 3);
        rect(gray, width, 116, 108, 3, 18);
        rect(gray, width, 100, 123, 19, 3);
        rect(gray, width, 126, 86, 5, 5);
        rect(gray, width, 126, 102, 5, 5);
        var staff = construct("Staff", 80f, 144f, 16f);
        var flat = construct("Component", 210, 100, 118, 82, 129, 105f, 110f);
        var method = OmrScoreInterpreter.class.getDeclaredMethod(
                "inlineBassClefOwnsFlat", byte[].class, int.class, int.class, flat.getClass(),
                float.class, float.class, staff.getClass());
        method.setAccessible(true);
        assertFalse((boolean) method.invoke(null, gray, width, height, flat, 85f, 137f, staff));
    }

    private static List<?> rejectedCommonHeads(boolean copy) throws Exception {
        int width = 480, height = 430;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int shift : new int[] {0, 180}) {
            for (int y = 80 + shift; y <= 144 + shift; y += 16)
                rect(gray, width, 20, y, 430, 1);
            if (shift != 0 && !copy) continue;
            for (int y = 88; y <= 136; y++)
                for (int x = 110; x <= 142; x++) {
                    double outer = Math.pow((x - 126) / 16.0, 2) + Math.pow((y - 112) / 24.0, 2);
                    double inner = Math.pow((x - 126) / 10.0, 2) + Math.pow((y - 112) / 18.0, 2);
                    if (outer <= 1 && inner >= 1 && !(x > 126 && Math.abs(y - 112) < 9))
                        gray[(y + shift) * width + x] = 0;
                }
        }
        var first = construct("Staff", 80f, 144f, 16f);
        var second = construct("Staff", 260f, 324f, 16f);
        var count = first.getClass().getDeclaredField("count");
        count.setAccessible(true);
        count.setInt(first, 2);
        count.setInt(second, 2);
        var heads = List.of(
                construct("Component", 23, 135, 141, 96, 102, 138f, 99f),
                construct("Component", 23, 135, 141, 276, 282, 138f, 279f),
                construct("Component", 80, 211, 226, 113, 120, 219f, 116f));
        var bars = List.of(new MeasureRegion(107f / width, .9f, 65f / height, 332f / height));
        var method = OmrScoreInterpreter.class.getDeclaredMethod("alignedCommonTimeHeads",
                byte[].class, byte[].class, int.class, int.class, List.class, List.class, List.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(null, gray, gray, width, height, heads, List.of(first, second), bars);
    }

    private static Object construct(String name, Object... args) throws Exception {
        var type = Class.forName(OmrScoreInterpreter.class.getName() + "$" + name);
        var ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private static void rect(byte[] gray, int width, int x, int y, int w, int h) {
        for (int yy = y; yy < y + h; yy++)
            Arrays.fill(gray, yy * width + x, yy * width + x + w, (byte) 0);
    }
}
