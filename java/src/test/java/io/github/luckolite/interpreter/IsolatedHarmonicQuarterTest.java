// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class IsolatedHarmonicQuarterTest {
    private static final int WIDTH = 440;
    private static final int HEIGHT = 420;
    private static final int GAP = 20;

    @Test
    public void wideHollowTouchNeedsAnInkJoinToTheQuarterShaft() {
        int left = 257;
        int right = 375;
        int bottom = 156;
        int touchX = 300;
        int touchY = 164;
        int stoppedX = 306;
        int stoppedY = 194;
        int shaft = 318;
        byte[] gray = new byte[WIDTH * HEIGHT];
        byte[] labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int y = bottom - 4 * GAP; y <= bottom; y += GAP) {
            Arrays.fill(gray, y * WIDTH + 42, y * WIDTH + right + 1, (byte) 0);
        }
        for (int y = bottom - 4 * GAP; y <= bottom; y++) {
            ink(gray, left - 11, left - 9, y);
            ink(gray, right - 9, right - 7, y);
            ink(gray, right - 1, right, y);
        }
        for (int y = touchY - 12; y <= touchY + 12; y++) {
            for (int x = touchX - 12; x <= touchX + 12; x++) {
                int distance = Math.abs(x - touchX) + Math.abs(y - touchY);
                if (distance >= 9 && distance <= 12) {
                    gray[y * WIDTH + x] = 0;
                    labels[y * WIDTH + x] = OmrMeasurePostProcessor.SYMBOL;
                }
            }
        }
        for (int y = touchY - 1; y <= touchY + 1; y++) {
            ink(gray, touchX + 10, shaft, y);
        }
        for (int y = 111; y <= stoppedY; y++) {
            ink(gray, shaft - 1, shaft, y);
        }
        for (int y = stoppedY - 5; y <= stoppedY + 5; y++) {
            for (int x = stoppedX - 10; x <= stoppedX + 10; x++) {
                if (Math.pow((x - stoppedX) / 10.0, 2) + Math.pow((y - stoppedY) / 5.0, 2) <= 1) {
                    gray[y * WIDTH + x] = 0;
                    labels[y * WIDTH + x] = OmrMeasurePostProcessor.NOTEHEAD;
                }
            }
        }
        var prior = region(42, left - 20, 36, 200);
        var cadence = region(left, right, 36, 200);
        var nextSystem = region(42, right, 240, 404);
        var bars = List.of(prior, cadence, nextSystem);
        var staff = new PlayingTechniqueDetector.Staff(bottom - 4 * GAP, bottom, GAP, 0, 1);
        var touch = note(touchX, touchY, left, right, -1, ScoreNoteEvent.DURATION_WHOLE);
        var stopped = note(stoppedX, stoppedY, left, right, -4, 1).withStemDirection(1);
        var decoded = List.of(touch, stopped);
        assertTrue(
                ArtificialHarmonics.isolatedQuarterTouch(
                        labels, gray, WIDTH, HEIGHT, bars, decoded, stopped, touch, cadence, staff,
                        stoppedX, stoppedY, GAP));
        assertEquals(
                List.of(stopped.withOctaveShift(2)),
                ArtificialHarmonics.apply(
                        labels, gray, WIDTH, HEIGHT, bars, decoded, List.of(staff)));

        for (int y = touchY - 4; y <= touchY + 4; y++) {
            Arrays.fill(gray, y * WIDTH + touchX + 13, y * WIDTH + shaft - 1, (byte) 255);
        }
        assertFalse(
                ArtificialHarmonics.isolatedQuarterTouch(
                        labels, gray, WIDTH, HEIGHT, bars, decoded, stopped, touch, cadence, staff,
                        stoppedX, stoppedY, GAP));
    }

    private static void ink(byte[] gray, int left, int right, int y) {
        Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }

    private static MeasureRegion region(int l, int r, int t, int b) {
        return new MeasureRegion(
                l / (float) WIDTH, r / (float) WIDTH, t / (float) HEIGHT, b / (float) HEIGHT);
    }

    private static ScoreNoteEvent note(
            int x, int y, int left, int right, int staffStep, float duration) {
        return new ScoreNoteEvent(
                1,
                (x - left) / (float) (right - left),
                staffStep,
                0,
                1,
                y / (float) HEIGHT,
                false,
                0,
                0,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                duration,
                0,
                0,
                0,
                ScoreNoteEvent.CLEF_TREBLE);
    }
}
