// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public class PaperBackgroundWhiteningTest {
    @Test
    public void cleanInkBypassesModelWithoutChangingSource() throws Exception {
        byte[] source = new byte[128 * 96];
        Arrays.fill(source, (byte) 255);
        for (int y = 20; y < 80; y += 12)
            Arrays.fill(source, y * 128 + 10, y * 128 + 118, (byte) 0);
        byte[] before = source.clone();
        var result =
                PaperBackgroundWhitening.apply(
                        source,
                        128,
                        96,
                        (features, w, h) -> {
                            throw new AssertionError("Clean page invoked model");
                        });
        assertSame(source, result.decoderGray());
        assertFalse(result.changed());
        assertEquals(result.rawSha256(), result.decoderSha256());
        assertArrayEquals(before, source);
    }

    @Test
    public void restoresKnownPaperAndKeepsOriginalInk() throws Exception {
        byte[] source = new byte[96 * 64];
        Arrays.fill(source, (byte) 204);
        source[3000] = 102;
        byte[] before = source.clone();
        var result =
                PaperBackgroundWhitening.apply(
                        source,
                        96,
                        64,
                        (features, w, h) -> {
                            assertEquals(2 * w * h, features.length);
                            assertEquals(.8f, features[0], .00001f);
                            return fields(w, h, .8f, .5f);
                        });
        assertTrue(result.changed());
        assertEquals(255, result.decoderGray()[0] & 255);
        assertEquals(0, result.decoderGray()[3000] & 255);
        assertArrayEquals(before, source);
        assertNotEquals(result.rawSha256(), result.decoderSha256());
    }

    @Test
    public void darkensFadedNotationOnWhitePaperWithoutTouchingCrispInkOrSpeckling()
            throws Exception {
        int width = 128, height = 96;
        byte[] source = new byte[width * height];
        byte[] labels = new byte[source.length];
        Arrays.fill(source, (byte) 255);
        stroke(source, labels, width, 12, 32, 115, 33, 178, 4);
        stroke(source, labels, width, 80, 20, 81, 47, 150, 1);
        stroke(source, labels, width, 35, 52, 43, 57, 165, 2);
        stroke(source, labels, width, 30, 70, 90, 71, 0, 4);
        stroke(source, labels, width, 30, 71, 90, 72, 150, 4);
        source[12 * width + 8] = (byte) 150;
        stroke(source, labels, width, 90, 4, 91, 5, 235, 5);
        byte[] before = source.clone();
        byte[] beforeLabels = labels.clone();

        var result =
                PaperBackgroundWhitening.apply(
                        source, labels, width, height,
                        (features, w, h) -> {
                            throw new AssertionError("White paper invoked the field model");
                        });

        byte[] output = result.decoderGray();
        assertTrue(result.changed());
        assertNotEquals(result.rawSha256(), result.decoderSha256());
        assertEquals(140, output[32 * width + 30] & 255);
        assertEquals(98, output[24 * width + 80] & 255);
        assertEquals(120, output[54 * width + 38] & 255);
        assertEquals(255, output[31 * width + 30] & 255);
        assertEquals(0, output[70 * width + 35] & 255);
        assertEquals(150, output[71 * width + 35] & 255);
        assertEquals(150, output[12 * width + 8] & 255);
        assertEquals(235, output[4 * width + 90] & 255);
        assertArrayEquals(before, source);
        assertArrayEquals(beforeLabels, labels);
    }

    @Test
    public void polishesRemainingFadedInkAfterWhitening() throws Exception {
        int width = 96, height = 64;
        byte[] source = new byte[width * height];
        byte[] labels = new byte[source.length];
        Arrays.fill(source, (byte) 204);
        stroke(source, labels, width, 12, 30, 80, 31, 168, 4);
        stroke(source, labels, width, 12, 37, 80, 38, 168, 0);
        var result =
                PaperBackgroundWhitening.apply(
                        source, labels, width, height, (features, w, h) -> fields(w, h, .8f, .8f));
        assertEquals(255, result.decoderGray()[0] & 255);
        assertEquals(178, result.decoderGray()[30 * width + 20] & 255);
        assertEquals(199, result.decoderGray()[37 * width + 20] & 255);
    }

    @Test
    public void validatesDimensionsBeforeInference() throws Exception {
        try {
            PaperBackgroundWhitening.apply(
                    new byte[2],
                    1,
                    3,
                    (f, w, h) -> {
                        throw new AssertionError();
                    });
            fail("Accepted mismatched raster");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rejectsNonFiniteModelOutput() throws Exception {
        byte[] source = new byte[64];
        Arrays.fill(source, (byte) 180);
        try {
            PaperBackgroundWhitening.apply(source, 8, 8, (f, w, h) -> fields(w, h, Float.NaN, .8f));
            fail("Accepted non-finite field");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rasterHashIncludesDimensions() throws Exception {
        byte[] source = new byte[64];
        assertNotEquals(
                PaperBackgroundWhitening.rasterSha256(source, 8, 8),
                PaperBackgroundWhitening.rasterSha256(source, 4, 16));
    }

    private static PaperBackgroundWhitening.Fields fields(int w, int h, float p, float c) {
        float[][] paper = new float[h][w], contrast = new float[h][w];
        for (int y = 0; y < h; y++) {
            Arrays.fill(paper[y], p);
            Arrays.fill(contrast[y], c);
        }
        return new PaperBackgroundWhitening.Fields(paper, contrast);
    }

    private static void stroke(
            byte[] gray, byte[] labels, int width, int left, int top, int right, int bottom,
            int tone, int label) {
        for (int y = top; y < bottom; y++) {
            Arrays.fill(gray, y * width + left, y * width + right, (byte) tone);
            Arrays.fill(labels, y * width + left, y * width + right, (byte) label);
        }
    }
}
