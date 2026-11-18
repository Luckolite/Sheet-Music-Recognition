// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.Test;

public class StaggeredSharpMembershipTest {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 180;
    private static final float GAP = 16;

    @Test
    public void rawSharpMustBelongToTheSameOriginalComponent() throws Exception {
        byte[] plane = new byte[WIDTH * HEIGHT];
        Arrays.fill(plane, (byte) 255);
        fill(plane, 56, 59, 82, 126);
        fill(plane, 64, 67, 81, 124);
        for (int x = 53; x < 71; x++) {
            int rise = Math.round((x - 53) * .30f);
            fill(plane, x, x + 1, 99 - rise, 103 - rise);
            fill(plane, x, x + 1, 115 - rise, 119 - rise);
        }
        int[] all = IntStream.range(0, plane.length).filter(i -> plane[i] == 0).toArray();
        int[] withoutRightSpine = Arrays.stream(all).filter(i -> i % WIDTH < 64).toArray();
        assertTrue(proves(plane, all, 105));
        assertFalse(proves(plane, withoutRightSpine, 105));
        assertFalse(proves(plane, all, 98));
    }

    private static boolean proves(byte[] plane, int[] members, float pitchY) throws Exception {
        Class<?> c = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var constructor = c.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        constructor.setAccessible(true);
        Object owner = constructor.newInstance(250, 75, 95, Math.round(pitchY) - 8,
                Math.round(pitchY) + 8, 85f, pitchY);
        var method = OmrScoreInterpreter.class.getDeclaredMethod("ownedStaggeredUpperSharp",
                byte[].class, int.class, int.class, c, float.class,
                int.class, int.class, int.class, int.class, int[].class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, plane, WIDTH, HEIGHT, owner, GAP,
                53, 74, 80, 129, members);
    }

    private static void fill(byte[] plane, int left, int right, int top, int bottom) {
        for (int y = top; y < bottom; y++)
            Arrays.fill(plane, y * WIDTH + left, y * WIDTH + right, (byte) 0);
    }
}
