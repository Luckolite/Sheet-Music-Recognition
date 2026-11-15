// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class PrintedDottedTripletDetachedDotTest {
    private static final int WIDTH = 160, HEIGHT = 160;

    @Test
    public void aDotCoreOnTheHeadShoulderCannotCountAsATripletDot() throws Exception {
        byte[] detached = new byte[WIDTH * HEIGHT];
        Arrays.fill(detached, (byte) 255);
        for (int y = 75; y <= 85; y++) {
            Arrays.fill(detached, y * WIDTH + 51, y * WIDTH + 68, (byte) 0);
        }
        for (int y = 77; y <= 83; y++) {
            Arrays.fill(detached, y * WIDTH + 78, y * WIDTH + 84, (byte) 0);
        }
        byte[] attached = detached.clone();
        Arrays.fill(attached, 80 * WIDTH + 68, 80 * WIDTH + 80, (byte) 0);
        assertNotNull(WrappedTripletDotInk.uniqueCore(attached, WIDTH, HEIGHT, 60, 80, 110, 16));
        assertTrue(isDetachedDot(detached));
        assertFalse(isDetachedDot(attached));
    }

    private static boolean isDetachedDot(byte[] gray) throws Exception {
        Class<?> owner = PrintedDottedTripletBar.class;
        var raster = Class.forName(owner.getName() + "$Raster");
        var head = Class.forName(owner.getName() + "$Head");
        var rasterData = raster.getDeclaredConstructor(byte[].class, int.class, int.class);
        var headData =
                head.getDeclaredConstructor(
                        int.class, ScoreNoteEvent.class, float.class, float.class);
        var check = owner.getDeclaredMethod("detachedDot", raster, head, float.class, float.class);
        rasterData.setAccessible(true);
        headData.setAccessible(true);
        check.setAccessible(true);
        return (boolean)
                check.invoke(
                        null,
                        rasterData.newInstance(gray, WIDTH, HEIGHT),
                        headData.newInstance(0, null, 60f, 80f),
                        110f,
                        16f);
    }
}
