// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class StaffAttachedStaccatoTest {
    private static final int WIDTH = 240, HEIGHT = 160;
    private static final NoteArticulationDetector.Anchor HEAD =
            new NoteArticulationDetector.Anchor(120f, 87f, 16f, 0, null, 46f, 110f, 0, 1);

    @Test
    public void aRoundBodyMayRestOnTheLowestStaffRule() throws Exception {
        var page = staff();
        assertNull(attached(page));

        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                if (dx * dx + dy * dy <= 14) page[(104 + dy) * WIDTH + 120 + dx] = 0;
            }
        }
        assertNotNull(attached(page));
        var marks =
                NoteArticulationDetector.detect(
                        new byte[page.length], page, WIDTH, HEIGHT, List.of(HEAD), page);
        assertEquals(NoteArticulation.STACCATO, marks[0]);

        for (int row = 108; row <= 111; row++)
            Arrays.fill(page, row * WIDTH, (row + 1) * WIDTH, (byte) 255);
        assertNull(attached(page));
    }

    private static Object attached(byte[] page) throws Exception {
        var proof =
                NoteArticulationDetector.class.getDeclaredMethod(
                        "dotAboveJoinedBottomRule",
                        NoteArticulationDetector.Anchor.class,
                        List.class,
                        byte[].class,
                        int.class,
                        int.class);
        proof.setAccessible(true);
        return proof.invoke(null, HEAD, List.of(HEAD), page, WIDTH, HEIGHT);
    }

    private static byte[] staff() {
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        for (int y : new int[] {46, 62, 78, 94, 108, 109, 110, 111})
            Arrays.fill(page, y * WIDTH + 12, (y + 1) * WIDTH - 12, (byte) 0);
        return page;
    }
}
