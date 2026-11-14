// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Separates descending grace flags from the oppositely sloping acciaccatura slash. */
final class SlashedGraceFlagInk {
    private SlashedGraceFlagInk() {}

    static int count(
            byte[] gray, int width, int height, float headX, float headY, int stemX, float gap) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || gap < 5
                || !Float.isFinite(gap)
                || !Float.isFinite(headX)
                || !Float.isFinite(headY)
                || stemX < 0
                || stemX >= width) return 0;
        int left = stemX + Math.max(2, Math.round(gap * .15f)),
                right = stemX + Math.max(5, Math.round(gap * .65f));
        int top = Math.max(1, Math.round(headY - gap * 2.8f)),
                bottom = Math.min(height - 2, Math.round(headY - gap * .45f));
        if (left < 1 || right >= width - 1 || top >= bottom) return 0;
        int threshold = BeamInkThreshold.at(gray, width, height, stemX, top, bottom, gap);
        int original = scan(gray, width, height, left, right, top, bottom, threshold, gap, null);
        if (original <= 1) return original;
        // A lower staff rule can complete a second apparent diagonal between the
        // stem and the returning tail of one flag. Only ignore rows independently
        // witnessed well outside BOTH sides of this miniature glyph.
        boolean[] rules = new boolean[height];
        boolean hasRule = false;
        int reach = Math.round(gap), span = Math.max(4, Math.round(gap * .5f));
        if (stemX - reach - span < 0 || stemX + reach + span >= width) return original;
        int ruleThreshold = Math.round(threshold * .8f);
        for (int y = Math.max(0, top - 1); y <= Math.min(height - 1, bottom + 1); y++) {
            int a = 0, b = 0;
            for (int dx = 0; dx < span; dx++) {
                if ((gray[y * width + stemX - reach - dx] & 255) < ruleThreshold) a++;
                if ((gray[y * width + stemX + reach + dx] & 255) < ruleThreshold) b++;
            }
            rules[y] = a >= span * .85f && b >= span * .85f;
            hasRule |= rules[y];
        }
        if (!hasRule) return original;
        int clean = scan(gray, width, height, left, right, top, bottom, threshold, gap, rules);
        return clean == 1 ? 1 : original;
    }

    private static int scan(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int top,
            int bottom,
            int threshold,
            float gap,
            boolean[] rules) {
        int maxRootSpacing = Math.max(2, Math.round(gap * .16f));
        int span = right - left;
        for (float contrast : new float[] {.8f, 1f}) {
            int th = Math.round(threshold * contrast);
            int bands = 0, previous = -1;
            boolean slash = false;
            for (int y = top; y <= bottom; y++) {
                boolean down = false;
                for (float slope = .75f; slope <= 2f; slope += .25f) {
                    if (!down && y + span * slope <= bottom) {
                        down = line(gray, width, height, left, right, y, slope, th, rules);
                    }
                    if (!slash) {
                        slash = line(gray, width, height, left, right, y, -slope, th, rules);
                    }
                    if (down && slash) break;
                }
                if (down) {
                    if (bands == 0 || y - previous > maxRootSpacing) bands++;
                    if (bands > 3) break;
                    previous = y;
                }
            }
            if (slash && bands > 0 && bands <= 3) return bands;
        }
        return 0;
    }

    private static boolean line(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int y,
            float slope,
            int threshold,
            boolean[] rules) {
        int misses = 0, total = Math.max(0, right - left + 1);
        float minimum = total * .90f;
        for (int x = left; x <= right; x++) {
            int yy = Math.round(y + (x - left) * slope);
            if (yy < 1 || yy >= height - 1) return false;
            boolean hit = false;
            for (int dy = -1; dy <= 1 && !hit; dy++)
                if ((rules == null || !rules[yy + dy])
                        && (gray[(yy + dy) * width + x] & 255) < threshold) hit = true;
            if (!hit && total - ++misses < minimum) return false;
        }
        return true;
    }
}
