// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Local meter geometry requires five parallel thin printed rails on the same clear strip. */
final class LocalMeterStaffWindows {
    record Window(int left, int right, int shift) {}

    static List<Window> detect(
            byte[] gray, int width, int height, int[] rows, float gap, float slope) {
        var out = new ArrayList<Window>();
        if (gray == null
                || gray.length != width * height
                || rows.length != 5
                || gap < 6
                || !Float.isFinite(gap)) return out;
        int radius = Math.max(1, Math.round(gap * .55f)),
                flank = Math.max(2, Math.round(gap * .24f));
        int minLength = Math.max(5, Math.round(gap * .65f));
        int padding = Math.round(gap * 3);
        int minShift = Math.max(1, Math.round(gap * .12f));
        int left = 0, anchor = 0, total = 0;
        int flankStride = flank * width;
        for (int x = 0; x < width; x++) {
            int best = 0, selected = Integer.MIN_VALUE;
            int tilt = Math.round(slope * (x - width * .5f));
            for (int d = -radius; d <= radius; d++) {
                int first = railContrast(gray, width, height, x, rows[0] + d + tilt, flank, flankStride);
                if (first == 0) continue;
                // Later shifts must strictly improve the current score. A single rail
                // contributes at most 255, even with white flanks and a black center.
                int score = first;
                for (int rail = 1; rail < rows.length; rail++) {
                    if (score + (rows.length - rail) * 255 <= best) {
                        score = 0;
                        break;
                    }
                    int contrast = railContrast(gray, width, height, x, rows[rail] + d + tilt,
                            flank, flankStride);
                    if (contrast == 0) {
                        score = 0;
                        break;
                    }
                    score += contrast;
                }
                if (score > best) {
                    best = score;
                    selected = d;
                }
            }
            if (x > left && (selected == Integer.MIN_VALUE || Math.abs(selected - anchor) > 1)) {
                addWindow(out, left, x, total, minLength, minShift, padding, width);
                left = x;
                total = 0;
            }
            if (selected == Integer.MIN_VALUE) {
                left = x + 1;
                continue;
            }
            if (left == x) anchor = selected;
            total += selected;
        }
        addWindow(out, left, width, total, minLength, minShift, padding, width);
        return out;
    }

    private static void addWindow(List<Window> windows, int left, int right, int total,
            int minLength, int minShift, int padding, int width) {
        int length = right - left;
        if (length < minLength) return;
        int shift = Math.round(total / (float) length);
        if (Math.abs(shift) > minShift)
            windows.add(new Window(Math.max(0, left - padding), Math.min(width, right + padding), shift));
    }

    private static int railContrast(
            byte[] gray, int width, int height, int x, int y, int flank, int flankStride) {
        if (y - flank < 0 || y + flank >= height) return 0;
        int at = y * width + x;
        int center = gray[at] & 255;
        if (center > 190) return 0;
        int surrounding = Math.min(gray[at - flankStride] & 255, gray[at + flankStride] & 255);
        int contrast = surrounding - center;
        return surrounding >= 200 && contrast >= 35 ? contrast : 0;
    }
}
