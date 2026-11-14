// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class MidstaffSixteenthTripleBarsTest {
    private static final int WIDTH = 230;
    private static final int HEIGHT = 140;
    private static final int GAP = 16;

    @Test
    public void recognizesOnlyTwoIndependentClosedRails() throws Exception {
        var image = new byte[WIDTH * HEIGHT];
        Arrays.fill(image, (byte) 255);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 4; i++) {
            int x = 48 + 39 * i, y = 48 + 4 * i;
            for (int dy = -6; dy <= 6; dy++)
                for (int dx = -10; dx <= 10; dx++)
                    if (dx * dx + 2 * dy * dy <= 100) image[(y + dy) * WIDTH + x + dx] = 0;
            rectangle(image, x - 10, y, x - 8, 109 + 4 * i, 0);
            notes.add(
                    new ScoreNoteEvent(
                                    0,
                                    (x - 30f) / 175f,
                                    10 - i,
                                    0,
                                    1,
                                    y / (float) HEIGHT,
                                    false,
                                    0,
                                    2,
                                    ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                                    0)
                            .withClef(30)
                            .withStemDirection(-1));
        }
        drawRail(image, 38, 157, 0);
        drawRail(image, 38, 157, 13);
        assertTrue(accepts(image, notes));
        var one = image.clone();
        rectangle(one, 80, 92, 97, 106, 255);
        assertFalse(accepts(one, notes));
        var unclosed = image.clone();
        drawRail(unclosed, 157, 188, 0);
        assertFalse(accepts(unclosed, notes));
        var wrongShaft = new ArrayList<>(notes);
        wrongShaft.set(2, wrongShaft.get(2).withStemDirection(1));
        assertFalse(accepts(image, wrongShaft));
    }

    private static boolean accepts(byte[] image, List<ScoreNoteEvent> notes) throws Exception {
        var region = new MeasureRegion(30f / WIDTH, 205f / WIDTH, 10f / HEIGHT, 138f / HEIGHT);
        Method method =
                MidstaffSixteenthTripleBars.class.getDeclaredMethod(
                        "closedRails",
                        byte[].class,
                        int.class,
                        int.class,
                        MeasureRegion.class,
                        List.class,
                        List.class,
                        float.class);
        method.setAccessible(true);
        return (boolean)
                method.invoke(
                        null,
                        image,
                        WIDTH,
                        HEIGHT,
                        region,
                        notes,
                        List.of(0, 1, 2, 3),
                        (float) GAP);
    }

    private static void drawRail(byte[] ink, int first, int last, int depth) {
        for (int x = first; x <= last; x++) {
            int y = 105 + Math.round((x - 38) * .10f) - depth;
            rectangle(ink, x, y - 4, x, y + 4, 0);
        }
    }

    private static void rectangle(byte[] ink, int lx, int top, int rx, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + lx, y * WIDTH + rx + 1, (byte) value);
    }
}
