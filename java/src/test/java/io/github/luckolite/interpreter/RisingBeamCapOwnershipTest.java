// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RisingBeamCapOwnershipTest {
    private static final int WIDTH = 160, HEIGHT = 150, GAP = 16;
    private static final int[] START = {48, 117, 1}, END = {77, 101, 1};
    private static final float SLOPE = (END[1] - START[1]) / (float) (END[0] - START[0]);

    @Test
    public void continuousRiseContainsEveryHeadMaskPixel() throws Exception {
        byte[] gray = rail();
        int[] body = members();
        assertTrue(owned(gray, body));
        int outside = 56 + WIDTH * (tip(56) + 7);
        int[] competingHead = Arrays.copyOf(body, body.length + 1);
        gray[outside] = 0;
        competingHead[body.length] = outside;
        assertFalse(owned(gray, competingHead));
    }

    @Test
    public void aBrokenRailCannotOwnTheMask() throws Exception {
        byte[] gray = rail();
        for (int y = tip(62) - 11; y <= tip(62) + 7; y++) gray[y * WIDTH + 62] = (byte) 255;
        assertFalse(owned(gray, members()));
    }

    private static boolean owned(byte[] gray, int[] members) throws Exception {
        var method = OmrScoreInterpreter.class.getDeclaredMethod("risingPairCapOwns",
                byte[].class, int.class, int.class, int[].class, int[].class, int[].class,
                float.class, float.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, gray, WIDTH, HEIGHT, members, START, END, SLOPE, (float) GAP);
    }

    private static int[] members() {
        var pixels = new ArrayList<Integer>();
        for (int x = 53; x <= 61; x++)
            for (int y = tip(x) - 4; y <= tip(x) + 1; y++)
                pixels.add(y * WIDTH + x);
        return pixels.stream().mapToInt(Integer::intValue).toArray();
    }

    private static byte[] rail() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int x = START[0]; x <= END[0]; x++)
            for (int y = tip(x) - 7; y <= tip(x) + 2; y++) gray[y * WIDTH + x] = 0;
        return gray;
    }

    private static int tip(int x) {
        return Math.round(START[1] + SLOPE * (x - START[0]));
    }
}
