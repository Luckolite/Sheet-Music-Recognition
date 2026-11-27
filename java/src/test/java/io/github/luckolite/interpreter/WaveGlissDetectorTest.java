// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original procedural sinusoidal ribbons, straight connectors and single-arch slurs. */
public class WaveGlissDetectorTest {
    private static final int W = 320, H = 260;
    private static final List<NoteSlideDetector.Head> HEADS =
            List.of(
                    new NoteSlideDetector.Head(60, 70, 16, 0, 0),
                    new NoteSlideDetector.Head(220, 190, 16, 0, 0));

    private static byte[] ribbon(int shape) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        double ux = .8, uy = .6;
        for (double along = 35; along <= 165; along += .25) {
            double normal =
                    shape == 0
                            ? 2.2 * Math.sin(along * 2 * Math.PI / 19)
                            : shape == 1 ? 0 : 8 * Math.sin((along - 35) * Math.PI / 130);
            double xx = 60 + ux * along - uy * normal, yy = 70 + uy * along + ux * normal;
            int x = (int) Math.round(xx), y = (int) Math.round(yy);
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) gray[(y + dy) * W + x + dx] = 0;
        }
        return gray;
    }

    @Test
    public void preparedRasterRetainsItsInkForTheFollowingSlideScan() {
        byte[] prepared = ribbon(0);
        byte[] original = prepared.clone();
        assertEquals(List.of(new WaveGlissDetector.Link(0, 1)),
                WaveGlissDetector.detectWithRemovedStaffLines(W, H, HEADS, prepared));
        assertArrayEquals(original, prepared);
    }

    @Test
    public void alternatingRibbonBindsOnlyItsConsecutiveEndpoints() {
        assertEquals(
                List.of(new WaveGlissDetector.Link(0, 1)),
                WaveGlissDetector.detect(ribbon(0), W, H, List.of(), HEADS));
    }

    @Test
    public void straightSlideAndSlurRemainDifferentFromSteppedGliss() {
        assertTrue(WaveGlissDetector.detect(ribbon(1), W, H, List.of(), HEADS).isEmpty());
        assertTrue(WaveGlissDetector.detect(ribbon(2), W, H, List.of(), HEADS).isEmpty());
    }

    @Test
    public void missingEndpointCannotInventGliss() {
        assertTrue(
                WaveGlissDetector.detect(ribbon(0), W, H, List.of(), HEADS.subList(0, 1))
                        .isEmpty());
    }

    @Test
    public void interveningHeadAndAmbiguousChordCannotAcquireGliss() {
        for (float x : new float[] {60, 140, 220}) {
            var heads =
                    List.of(
                            HEADS.get(0),
                            HEADS.get(1),
                            new NoteSlideDetector.Head(x, 140, 16, 0, 0));
            assertTrue(WaveGlissDetector.detect(ribbon(0), W, H, List.of(), heads).isEmpty());
        }
    }

    @Test
    public void endpointOnAnotherStaffCannotAcquireGliss() {
        var heads = List.of(HEADS.get(0), new NoteSlideDetector.Head(220, 190, 16, 1, 0));
        assertTrue(WaveGlissDetector.detect(ribbon(0), W, H, List.of(), heads).isEmpty());
    }

    @Test
    public void staffCrossingPreservesOriginalWaveAndDoesNotAlterSourcePixels() {
        var gray = ribbon(0);
        for (int x = 0; x < W; x++) gray[130 * W + x] = 0;
        var original = gray.clone();
        assertEquals(
                List.of(new WaveGlissDetector.Link(0, 1)),
                WaveGlissDetector.detect(
                        gray, W, H, List.of(new NoteSlideDetector.Staff(98, 162, 16)), HEADS));
        assertArrayEquals(original, gray);
    }

    @Test public void ribbonInteriorOwnsAnAliasedHeadOnlyWhileItsOscillationSurvives() {
        assertTrue(interiorHead(false, false));
        assertFalse(interiorHead(true, false));
        assertFalse(interiorHead(false, true));
    }

    private static boolean interiorHead(boolean straight, boolean independentOval) {
        byte[] labels = new byte[W * H], gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int x = 79; x <= 245; x++) {
            double wave = straight ? 0 : 2.4 * Math.sin((x - 79) * Math.PI / 6);
            int center = (int) Math.round(128 - (x - 79) * .09 + wave);
            for (int y = center - 1; y <= center + 1; y++) {
                gray[y * W + x] = 0;
                labels[y * W + x] = x >= 153 && x <= 173
                        ? OmrMeasurePostProcessor.NOTEHEAD : OmrMeasurePostProcessor.SYMBOL;
            }
        }
        if (independentOval) {
            for (int y = 109; y <= 131; y++)
                for (int x = 153; x <= 173; x++)
                    if (Math.hypot((x - 163) / 9d, (y - 121) / 8d) <= 1) {
                        gray[y * W + x] = 0;
                        labels[y * W + x] = OmrMeasurePostProcessor.NOTEHEAD;
                    }
        }
        int area = 0;
        for (int y = 109; y <= 132; y++)
            for (int x = 153; x <= 173; x++)
                if (labels[y * W + x] == OmrMeasurePostProcessor.NOTEHEAD) area++;
        return WaveGlissDetector.ownsInterior(labels, gray, W, H, 153, 109, 173, 132, area, 16);
    }

    @Test public void aProvedWaveCanJoinOnlyAdjacentBarsOnTheSameStaff() {
        var source = HEADS.get(0);
        var target = new NoteSlideDetector.Head(220, 190, 16, 0, 1);
        var intervening = new NoteSlideDetector.Head(185, 166, 16, 0, 1);
        var anotherBar = new NoteSlideDetector.Head(220, 190, 16, 0, 2);
        assertEquals(List.of(new WaveGlissDetector.Link(0, 1)),
                WaveGlissDetector.detect(ribbon(0), W, H, List.of(), List.of(source, target)));
        assertTrue(WaveGlissDetector.detect(ribbon(0), W, H, List.of(),
                List.of(source, target, intervening)).isEmpty());
        assertTrue(WaveGlissDetector.detect(ribbon(0), W, H, List.of(),
                List.of(source, anotherBar)).isEmpty());
        assertTrue(WaveGlissDetector.detect(ribbon(1), W, H, List.of(),
                List.of(source, target)).isEmpty());
    }
}
