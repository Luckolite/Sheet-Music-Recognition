// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class ClosedTripletSharpDotTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 100;
    private static final float GAP = 12;
    private static final int NEXT_X = 105;
    private static final int NEXT_Y = 44;

    @Test
    public void dotWindowBelongsToAnOffCenterPrintedSharp() {
        byte[] gray = sharp();
        byte[] labels = new byte[gray.length];
        markSharp(gray, labels);
        assertTrue(owns(gray, labels, new WrappedTripletDotInk.Center(80, 55)));
    }

    @Test
    public void separateDotAndIsolatedShaftCannotClaimTheNextAttack() {
        byte[] gray = sharp();
        byte[] labels = new byte[gray.length];
        markSharp(gray, labels);
        gray[55 * WIDTH + 83] = 0;
        labels[55 * WIDTH + 83] = OmrMeasurePostProcessor.SYMBOL;
        assertFalse(owns(gray, labels, new WrappedTripletDotInk.Center(83, 55)));
        Arrays.fill(gray, (byte) 255);
        for (int y = 33; y <= 56; y++) gray[y * WIDTH + 80] = 0;
        assertFalse(owns(gray, labels, new WrappedTripletDotInk.Center(80, 55)));
    }

    private static boolean owns(byte[] gray, byte[] labels, WrappedTripletDotInk.Center center) {
        return WrappedEighthTriplets.closedBarSharpOwnsDot(
                gray, labels, WIDTH, HEIGHT, center, NEXT_X, NEXT_Y, GAP);
    }

    private static void markSharp(byte[] gray, byte[] labels) {
        for (int i = 0; i < gray.length; i++) {
            if (gray[i] == 0) labels[i] = OmrMeasurePostProcessor.CLEF_OR_KEY;
        }
    }

    private static byte[] sharp() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 33; y <= 56; y++) gray[y * WIDTH + 80] = 0;
        for (int y = 31; y <= 54; y++) gray[y * WIDTH + 86] = 0;
        for (int x = 76; x <= 91; x++) {
            for (int y = 40; y <= 42; y++) gray[y * WIDTH + x] = 0;
            for (int y = 47; y <= 49; y++) gray[y * WIDTH + x] = 0;
        }
        return gray;
    }
}
