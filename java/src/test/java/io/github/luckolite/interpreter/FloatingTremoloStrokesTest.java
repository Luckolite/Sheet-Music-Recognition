// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import org.junit.Test;

public class FloatingTremoloStrokesTest {
    private static final int WIDTH = 260, HEIGHT = 170;

    @Test
    public void readsDetachedSlashesAcrossStaffRules() {
        assertNotNull(read(3, -.18, true, true, false));
        assertNotNull(read(3, .23, true, true, false));
    }

    @Test
    public void requiresThreePhysicalAndSemanticallySupportedStrokes() {
        assertNull(read(2, -.18, true, true, false));
        assertNull(read(4, -.18, true, true, false));
        assertNull(read(3, -.18, false, true, false));
        assertNull(read(3, -.18, true, false, false));
    }

    @Test
    public void rejectsHorizontalOrEndpointContactingBeams() {
        assertNull(read(3, 0, true, true, false));
        assertNull(read(3, -.18, true, true, true));
    }

    private static FloatingTremoloStrokes.Mark read(
            int count, double slope, boolean ink, boolean semantic, boolean touches) {
        byte[] labels = new byte[WIDTH * HEIGHT], gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 42; y <= 110; y += 17)
            Arrays.fill(gray, y * WIDTH, (y + 1) * WIDTH, (byte) 0);
        for (int i = 0; i < count; i++)
            for (int x = touches ? 41 : 69; x <= 148; x++) {
                int y = 52 + i * 13 + (int) Math.round(slope * (x - 109));
                for (int dy = -3; dy <= 3; dy++) {
                    int p = (y + dy) * WIDTH + x;
                    if (ink) gray[p] = 0;
                    if (semantic) labels[p] = 5;
                }
            }
        return FloatingTremoloStrokes.find(labels, gray, WIDTH, HEIGHT, 40, 177, 34, 110, 17);
    }
}
