// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import org.junit.Test;

public class ClosedDottedThirdsMaskTest {
    private static final int WIDTH = 320, HEIGHT = 240;

    @Test
    public void beamMaskRequiresBroadConnectedRawInkAndItsOwnCentroid() throws Exception {
        byte[] labels = new byte[WIDTH * HEIGHT];
        byte[] gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        paint(labels, 77, 112, 105, 117, OmrMeasurePostProcessor.NOTEHEAD);
        paint(gray, 77, 112, 105, 117, (byte) 0);
        assertNotNull(mask(labels, gray, 94.5f, 111));
        assertNull(mask(labels, gray, 93.5f, 111));
        byte[] missingInk = gray.clone();
        paint(missingInk, 79, 99, 106, 116, (byte) 255);
        assertNull(mask(labels, missingInk, 94.5f, 111));

        byte[] compact = new byte[labels.length];
        paint(compact, 86, 103, 105, 117, OmrMeasurePostProcessor.NOTEHEAD);
        assertNull(mask(compact, gray, 94.5f, 111));
    }

    private static Object mask(byte[] labels, byte[] gray, float x, float y) throws Exception {
        Class<?> type = ClosedDottedThirds.class;
        Class<?> head = Class.forName(type.getName() + "$Head");
        var data =
                head.getDeclaredConstructor(
                        int.class, ScoreNoteEvent.class, float.class, float.class);
        var proof =
                type.getDeclaredMethod(
                        "beamMask",
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        head,
                        float.class);
        data.setAccessible(true);
        proof.setAccessible(true);
        return proof.invoke(
                null, labels, gray, WIDTH, HEIGHT, data.newInstance(0, null, x, y), 16f);
    }

    private static void paint(byte[] raster, int left, int right, int top, int bottom, byte ink) {
        for (int row = top; row <= bottom; row++) {
            Arrays.fill(raster, row * WIDTH + left, row * WIDTH + right + 1, ink);
        }
    }
}
