// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

/** Rejects artwork and text that segmentation mistook for a score page. */
final class PrintedPageEvidence {
    private PrintedPageEvidence() {}

    static ScorePageInterpretation rejectStafflessPage(
            ScorePageInterpretation score,
            byte[] gray,
            byte[] sourceGray,
            int width,
            int height,
            boolean hasTabRows) {
        if (score == null || score.measures().isEmpty() || hasTabRows || gray == null
                || width <= 0 || height <= 0 || gray.length != (long) width * height)
            return score;
        // Paper correction can make image texture look like rows and notes. Judge a
        // letterboxed continuous-tone page on the source raster before that correction.
        if (isLetterboxedArtwork(sourceGray, width, height)
                && RawStaffLineDetector.detect(sourceGray, width, height).isEmpty())
            return new ScorePageInterpretation(List.of(), List.of());
        // A silent printed bar still needs its staff; ordinarily the decoder's raster
        // preserves faded ink better than the source raster.
        return score.notes().isEmpty() && RawStaffLineDetector.detect(gray, width, height).isEmpty()
                ? new ScorePageInterpretation(List.of(), List.of())
                : score;
    }

    private static boolean isLetterboxedArtwork(byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height || width < 64 || height < 64)
            return false;
        int step = Math.max(1, width / 128);
        if (!blackRow(gray, width, 0, step) || !blackRow(gray, width, height - 1, step))
            return false;
        int top = 1;
        while (top < height && blackRow(gray, width, top, step)) top++;
        int bottom = height - 2;
        while (bottom >= top && blackRow(gray, width, bottom, step)) bottom--;
        if (top < height / 24f || height - 1 - bottom < height / 24f
                || bottom - top + 1 < height * .6f)
            return false;
        int midtone = 0;
        int varied = 0;
        int sampled = 0;
        for (int y = top; y <= bottom; y += step) {
            int previous = 0;
            for (int x = 0; x < width; x += step) {
                int tone = gray[y * width + x] & 255;
                if (tone > 32 && tone < 220) {
                    midtone++;
                    if (previous > 32 && previous < 220 && Math.abs(tone - previous) > 15)
                        varied++;
                }
                previous = tone;
                sampled++;
            }
        }
        return midtone > sampled * .45f && varied > sampled * .10f;
    }

    private static boolean blackRow(byte[] gray, int width, int y, int step) {
        int light = 0;
        int allowance = (width / step) / 50;
        for (int x = 0; x < width; x += step)
            if ((gray[y * width + x] & 255) >= 24 && ++light > allowance) return false;
        return true;
    }
}
