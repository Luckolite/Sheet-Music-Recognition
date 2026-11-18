// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class ClosedHeadFlagRootTest {
    private static final int W = 180, H = 170;
    private static final float GAP = 16f;
    private static final int[] SHAFT = {73, 118, 1};

    @Test
    public void separatedInkCannotShortenAnIndependentlyHollowHead() throws Exception {
        assertFalse(ownsFlag(page(true, false)));
    }

    @Test
    public void aRealRootPreservesFlagOwnership() throws Exception {
        assertTrue(ownsFlag(page(true, true)));
    }

    @Test
    public void opaqueHeadDoesNotUseTheHollowHeadException() throws Exception {
        assertTrue(ownsFlag(page(false, false)));
    }

    private static byte[] page(boolean hollow, boolean rooted) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y = 43; y <= 61; y++) {
            for (int x = 73; x <= 95; x++) {
                int dx = x - 84, dy = y - 52;
                double oval = dx * dx / 121.0 + dy * dy / 64.0;
                if (oval <= 1 && (!hollow || oval > .42)) gray[y * W + x] = 0;
            }
        }
        fill(gray, 72, 52, 75, 118);
        for (int y = 101; y <= 118; y++) {
            int left = y < 111 ? 81 : 83;
            fill(gray, left, y, 92, y);
        }
        if (rooted) fill(gray, 73, 108, 85, 111);
        return gray;
    }

    private static boolean ownsFlag(byte[] gray) throws Exception {
        Class<?> caller = OmrScoreInterpreter.class;
        Class<?> component = Class.forName(caller.getName() + "$Component");
        var constructor = component.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object head = constructor.newInstance(260,73,95,43,61,84f,52f);
        var verify = caller.getDeclaredMethod(
                "ownsFlagBesideSustainedHead", byte[].class, byte[].class, int.class, int.class,
                component, float.class, float.class, float.class, int[].class);
        verify.setAccessible(true);
        return (boolean) verify.invoke(null, new byte[W * H], gray, W, H, head, GAP, 0f, 2f, SHAFT);
    }

    private static void fill(byte[] gray, int l, int t, int r, int b) {
        for (int y=t; y<=b; y++) Arrays.fill(gray, y * W + l, y * W + r + 1, (byte) 0);
    }
}
