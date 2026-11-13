// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Keeps shaded paper out of the narrow ink bands used to count beams. */
final class BeamInkThreshold {
    private BeamInkThreshold() {}

    static int at(byte[] gray, int width, int height, int x, int top, int bottom, float gap) {
        if (gray == null || gap < 3) return 165;
        int margin = Math.max(4, Math.round(gap));
        int left = Math.max(0, x - margin * 2), right = Math.min(width - 1, x + margin * 2);
        int first = Math.max(0, top - margin), last = Math.min(height - 1, bottom + margin);
        if (left > right || first > last) return 40;
        int total = (right - left + 1) * (last - first + 1);
        int target = (total * 3 + 3) / 4;
        int bright = 0, dark = 0;
        // The clamped result can often be decided from one end of the tone range.
        for (int y = first; y <= last; y++) {
            int row = y * width;
            for (int xx = left; xx <= right; xx++) {
                int tone = gray[row + xx] & 255;
                if (tone >= 197 && ++bright > total - target) return 165;
                if (tone <= 72 && ++dark >= target) return 40;
            }
        }

        // Only tones that change the clamped result need histogram bins.
        int[] histogram = new int[124];
        for (int y = first; y <= last; y++) {
            int row = y * width;
            for (int xx = left; xx <= right; xx++) {
                int tone = gray[row + xx] & 255;
                if (tone > 72 && tone < 197) histogram[tone - 73]++;
            }
        }
        int seen = dark;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i];
            if (seen >= target) return i + 41;
        }
        return 165;
    }
}
