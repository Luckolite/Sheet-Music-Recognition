// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ClosedHalfOctaveCadenceTest {
    private static final int WIDTH = 420;
    private static final int HEIGHT = 220;
    private static final float GAP = 16;

    @Test
    public void requiresACompleteDetachedOneAtTheEndOfTheShaft() throws Exception {
        byte[] page = paper();
        paint(page, 100, 102, 53, 95, 0);
        paint(page, 0, WIDTH - 1, 79, 80, 0);
        paint(page, 112, 114, 87, 104, 0);
        paint(page, 109, 112, 90, 91, 0);
        paint(page, 110, 117, 103, 104, 0);
        var detached =
                OmrScoreInterpreter.class.getDeclaredMethod(
                        "detachedOneBesideDownstem",
                        byte[].class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        float.class);
        detached.setAccessible(true);
        assertTrue((boolean) detached.invoke(null, page, WIDTH, HEIGHT, 100, 95, GAP));

        byte[] rooted = page.clone();
        paint(rooted, 100, 113, 89, 91, 0);
        assertFalse((boolean) detached.invoke(null, rooted, WIDTH, HEIGHT, 100, 95, GAP));

        byte[] erased = page.clone();
        paint(erased, 108, 120, 86, 105, 255);
        assertFalse((boolean) detached.invoke(null, erased, WIDTH, HEIGHT, 100, 95, GAP));
    }

    @Test
    public void wideHalfOctaveCadenceRequiresBothIndependentBarlines() {
        byte[] page = paper();
        for (int y = 80; y <= 144; y += 16) paint(page, 26, 374, y, y + 1, 0);
        paint(page, 95, 97, 80, 145, 0);
        paint(page, 247, 249, 80, 145, 0);
        paint(page, 254, 256, 80, 145, 0);

        assertEquals(2, capturedCapacity(page));
        byte[] noOpen = page.clone();
        paint(noOpen, 91, 102, 80, 145, 255);
        assertEquals(0, capturedCapacity(noOpen));
        byte[] noClose = page.clone();
        paint(noClose, 244, 259, 80, 145, 255);
        assertEquals(0, capturedCapacity(noClose));
    }

    private static int capturedCapacity(byte[] page) {
        var measures =
                List.of(
                        new MeasureRegion(105f / WIDTH, 241f / WIDTH, .2f, .83f),
                        new MeasureRegion(259f / WIDTH, 372f / WIDTH, .2f, .83f));
        var score =
                new ScorePageInterpretation(
                        measures,
                        List.of(halfNote(8, 94), halfNote(15, 38)),
                        1,
                        List.of(),
                        List.of(),
                        List.of());
        var staffs = List.of(new PlayingTechniqueDetector.Staff(80, 144, GAP, 0, 1));
        return PrintedCadenceDurations.capture(score, page, WIDTH, HEIGHT, staffs)
                .measures()
                .get(0)
                .cadenceQuarterBeats();
    }

    private static ScoreNoteEvent halfNote(int step, int y) {
        return new ScoreNoteEvent(
                0,
                .40f,
                step,
                0,
                1,
                y / (float) HEIGHT,
                false,
                0,
                0,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                2,
                1,
                0,
                0,
                30,
                false,
                0,
                false,
                0,
                0,
                1,
                -1);
    }

    private static byte[] paper() {
        byte[] page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        return page;
    }

    private static void paint(byte[] page, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
        }
    }
}
