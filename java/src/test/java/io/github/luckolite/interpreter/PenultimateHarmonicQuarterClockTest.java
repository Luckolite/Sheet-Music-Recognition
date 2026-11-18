// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PenultimateHarmonicQuarterClockTest {
    private static final int WIDTH = 720, HEIGHT = 620;
    private static final int TOP = 402, BOTTOM = 466;

    @Test
    public void twoIndependentlyUnflaggedWrittenShaftsCloseTheRecognizedSecondQuarter() {
        var source = print();
        assertEquals(3d, onset(source, source, new ScoreMeterChange(0, 2, 4)), 0);
        assertEquals(3.25d, onset(source, source, new ScoreMeterChange(0, 3, 4)), 0);
    }

    @Test
    public void aMissingRingOrJoinedShaftInEitherPlanePreservesTheClock() {
        var original = print();
        var missing = original.clone();
        rectangle(missing, 396, 420, 500, 523, 255);
        var joined = original.clone();
        for (int x = 351; x <= 394; x++) {
            int y = Math.round(451 + (479 - 451) * (x - 351) / 43f);
            rectangle(joined, x, x, y - 2, y + 2, 0);
        }
        var meter = new ScoreMeterChange(0, 2, 4);
        for (var changed : List.of(missing, joined)) {
            assertEquals(3.25d, onset(changed, original, meter), 0);
            assertEquals(3.25d, onset(original, changed, meter), 0);
        }
    }

    private static double onset(byte[] gray, byte[] raw, ScoreMeterChange meter) {
        var bar = new MeasureRegion(339f / WIDTH, 433f / WIDTH, 369f / HEIGHT, 500f / HEIGHT);
        var previous = new MeasureRegion(242f / WIDTH, 320f / WIDTH, bar.top(), bar.bottom());
        var following = new MeasureRegion(450f / WIDTH, 544f / WIDTH, bar.top(), bar.bottom(), 1);
        var owner =
                new ScoreNoteEvent(
                                1,
                                (401f - 339) / (433 - 339),
                                8,
                                0,
                                1,
                                402f / HEIGHT,
                                false,
                                0,
                                0,
                                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                                1,
                                1,
                                0,
                                0,
                                ScoreNoteEvent.CLEF_TREBLE)
                        .withStemDirection(-1);
        var score =
                new ScorePageInterpretation(
                        List.of(previous, bar, following),
                        List.of(owner),
                        1,
                        List.of(),
                        List.of(),
                        List.of(meter),
                        List.of());
        Map<String, Object> event =
                new HashMap<>(
                        Map.of(
                                "measureIndex",
                                1,
                                "durationFallback",
                                false,
                                "startBeat",
                                3.25d,
                                "durationBeats",
                                1d));
        PenultimateHarmonicQuarterClock.place(
                score,
                List.of(event),
                gray,
                raw,
                WIDTH,
                HEIGHT,
                new float[] {2, 2, 1},
                new double[] {0, 2, 4, 5});
        return ((Number) event.get("startBeat")).doubleValue();
    }

    private static byte[] print() {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += 16) rectangle(ink, 206, 560, y, y + 1, 0);
        for (int x : new int[] {329, 442}) rectangle(ink, x, x + 1, TOP, BOTTOM, 0);
        rectangle(ink, 350, 351, 384, 454, 0);
        rectangle(ink, 393, 395, 399, 483, 0);
        for (int[] center : new int[][] {{361, 363}, {361, 512}, {404, 378}, {407, 512}}) {
            for (int x = -8; x <= 8; x++) {
                for (int y = -10; y <= 10; y++) {
                    float radius = x * x / 49f + y * y / 81f;
                    if (radius >= .47f && radius <= 1.06f)
                        ink[(center[1] + y) * WIDTH + center[0] + x] = 0;
                }
            }
        }
        return ink;
    }

    private static void rectangle(byte[] ink, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
