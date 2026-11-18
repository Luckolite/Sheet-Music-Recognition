// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class OpeningOctaveDotTest {
    private static final int WIDTH = 1120, HEIGHT = 340, GAP = 16;
    private static final int LEFT = 388, RIGHT = 992, BOTTOM = 222;
    private static final int UPPER = BOTTOM - 2 * GAP, LOWER = UPPER + 7 * GAP / 2;
    private static final int FIRST = LEFT + 25, ADVANCE = 36;

    @Test
    public void closedUpstemQuartetSuppliesTheOriginalSixteenthDuration() {
        var page = score();
        var source = image();
        var applied = ClosedSixteenthDyads.apply(page, source, source, WIDTH, HEIGHT);
        assertEquals(0, applied.notes().get(0).augmentationDots());
        assertEquals(0, applied.notes().get(1).augmentationDots());
        assertEquals(page.notes().subList(2, 32), applied.notes().subList(2, 32));
        assertEquals(page.measures(), applied.measures());
    }

    @Test
    public void realFirstDotAndMissingRawRailAreHeld() {
        var page = score();
        var source = image();
        var marked = source.clone();
        rectangle(marked, FIRST + 16, FIRST + 20, LOWER - 7, LOWER - 3, 0);
        assertSame(page, ClosedSixteenthDyads.apply(page, source, marked, WIDTH, HEIGHT));
        var broken = source.clone();
        rectangle(broken, FIRST + 23, FIRST + 28, UPPER - 63, UPPER - 37, 255);
        assertSame(page, ClosedSixteenthDyads.apply(page, source, broken, WIDTH, HEIGHT));
    }

    private static ScorePageInterpretation score() {
        var measures = List.of(bar(110, LEFT - 18), bar(LEFT, RIGHT));
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 16; i++) {
            notes.add(head(i, UPPER, 4));
            notes.add(head(i, LOWER, -3));
        }
        return new ScorePageInterpretation(measures, notes, 0, List.of(), List.of(),
                List.of(new ScoreMeterChange(0, 4, 4)), List.of());
    }

    private static MeasureRegion bar(int left, int right) {
        return new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH,
                (BOTTOM - 4f * GAP - 33) / HEIGHT, (BOTTOM + 35f) / HEIGHT);
    }

    private static ScoreNoteEvent head(int slot, int y, int step) {
        float position = (FIRST + ADVANCE * slot - LEFT) / (float) (RIGHT - LEFT);
        return new ScoreNoteEvent(1, position, step, 0, 1, y / (float) HEIGHT, false,
                slot == 0 ? 1 : 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0,
                ScoreNoteEvent.CLEF_TREBLE).withStemDirection(slot < 4 ? 1 : -1);
    }

    private static byte[] image() {
        byte[] pixels = new byte[WIDTH * HEIGHT];
        Arrays.fill(pixels, (byte) 255);
        for (int y = BOTTOM - 4 * GAP; y <= BOTTOM; y += GAP) rectangle(pixels, 96, RIGHT + 5, y, y, 0);
        rectangle(pixels, LEFT - 11, LEFT - 8, BOTTOM - 4 * GAP, BOTTOM, 0);
        rectangle(pixels, RIGHT + 2, RIGHT + 5, BOTTOM - 4 * GAP, BOTTOM, 0);
        for (int slot = 0; slot < 16; slot++) {
            int x = FIRST + ADVANCE * slot, direction = slot < 4 ? 1 : -1;
            int shaft = x + direction * 10;
            if (direction > 0) rectangle(pixels, shaft - 1, shaft + 1, UPPER - 60, LOWER, 0);
            else rectangle(pixels, shaft - 1, shaft + 1, UPPER, LOWER + 53, 0);
            for (int y : new int[] {UPPER, LOWER}) {
                for (int dx = -10; dx <= 10; dx++)
                    for (int dy = -5; dy <= 5; dy++)
                        if (dx * dx / 100f + dy * dy / 25f <= 1)
                            pixels[(y + dy) * WIDTH + x + dx] = 0;
            }
            if (slot % 4 != 0) {
                int tip = direction > 0 ? UPPER - 60 : LOWER + 53;
                int near = tip + direction * 13;
                rectangle(pixels, shaft - ADVANCE, shaft, Math.min(tip, tip + 5 * direction),
                        Math.max(tip, tip + 5 * direction), 0);
                rectangle(pixels, shaft - ADVANCE, shaft, Math.min(near, near + 5 * direction),
                        Math.max(near, near + 5 * direction), 0);
            }
        }
        return pixels;
    }

    private static void rectangle(byte[] pixels, int a, int b, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            for (int x = a; x <= b; x++) pixels[y * WIDTH + x] = (byte) value;
    }
}
