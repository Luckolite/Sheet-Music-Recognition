// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

/** Reuses independently proved page glyphs when one serif lobe remains visibly open. */
final class MatchedDetachedThreeShape {
    private static final float MIN_REFERENCE = .84f;
    private static final float EIGHT_MARGIN = .12f;

    private MatchedDetachedThreeShape() {}

    record Reference(List<float[]> raw, List<float[]> normalized) {}

    static boolean proves(byte[] raster, int stride, int left, int top, int right, int bottom,
            List<Reference> references, boolean normalized) {
        int width = right - left + 1, height = bottom - top + 1;
        if (!FingeringThreeInk.solidInk(raster, stride, null, left, top, width, height, .05f, .30f, .18f, .35f, 32)
                || !FingeringThreeInk.solidInk(raster, stride, null, left, top, width, height, .64f, .92f, .25f, .47f, 32)
                || !FingeringThreeInk.solidInk(raster, stride, null, left, top, width, height, .64f, .92f, .53f, .80f, 32)
                || !(FingeringThreeInk.opening(raster, stride, null, left, top, width, height, .17f, .46f)
                || FingeringThreeInk.opening(raster, stride, null, left, top, width, height, .57f, .83f)))
            return false;
        float[] shape = vector(raster, stride, left, top, right, bottom);
        float required = Math.max(MIN_REFERENCE, JoinedDigitTemplates.maxEightSimilarity(shape) + EIGHT_MARGIN);
        int matches = 0;
        for (var reference : references) {
            for (float[] variant : normalized ? reference.normalized() : reference.raw()) {
                if (similarity(shape, variant) < required) continue;
                if (++matches == 2) return true;
                break;
            }
        }
        return false;
    }

    static float[] vector(byte[] raster, int stride, int left, int top, int right, int bottom) {
        final int sampleWidth = 16, sampleHeight = 24;
        float[] vector = new float[sampleWidth * sampleHeight];
        float xScale = (right - left + 1) / (float) sampleWidth;
        float yScale = (bottom - top + 1) / (float) sampleHeight;
        float mass = 0;
        for (int y = 0; y < sampleHeight; y++) {
            float yy = Math.max(top, Math.min(bottom, top + (y + .5f) * yScale - .5f));
            int y0 = (int) yy, y1 = Math.min(bottom, y0 + 1);
            for (int x = 0; x < sampleWidth; x++) {
                float xx = Math.max(left, Math.min(right, left + (x + .5f) * xScale - .5f));
                int x0 = (int) xx, x1 = Math.min(right, x0 + 1);
                float a = interpolate(raster[y0 * stride + x0] & 255, raster[y0 * stride + x1] & 255, xx - x0);
                float b = interpolate(raster[y1 * stride + x0] & 255, raster[y1 * stride + x1] & 255, xx - x0);
                float weight = 128f - interpolate(a, b, yy - y0);
                vector[y * sampleWidth + x] = weight;
                mass += weight * weight;
            }
        }
        float norm = (float) Math.sqrt(mass);
        if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }

    private static float interpolate(float a, float b, float fraction) {
        return a + (b - a) * fraction;
    }

    private static float similarity(float[] a, float[] b) {
        float dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }
}
