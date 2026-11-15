// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** A cropped arpeggio remains part of a longer repeatedly reversing stroke. */
final class RestVerticalWave {
    private RestVerticalWave() {}

    static boolean crosses(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int top,
            int bottom,
            float gap) {
        return crosses(gray, width, height, left, right, top, bottom, gap, null);
    }

    static boolean crosses(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int top,
            int bottom,
            float gap,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        if (gray == null || gap < 4 || left < 0 || right >= width || top < 0 || bottom >= height)
            return false;
        int first = Math.max(0, Math.round(top - gap * 3)),
                last = Math.min(height - 1, Math.round(bottom + gap * 3));
        int ruleLeft = Math.round(left - gap), ruleRight = Math.round(right + gap);
        boolean hasRuleFlanks =
                left - gap >= 0 && right + gap < width && ruleLeft >= 0 && ruleRight < width;
        int blankLimit = Math.max(1, Math.round(gap * .3f));
        float excursion = Math.max(1.5f, gap * .12f);
        boolean completeRaster = width > 0 && height > 0 && (long) width * height == gray.length;
        boolean unmeteredRaster = quarterBudget == null && completeRaster;
        long rowWork = 1L + 2L * (right - (long) left + 1);
        float latestStart = top + gap * .3f;
        float earliestEnd = bottom - gap * .3f;
        float minimumLength = gap * 3.7f;
        if (unmeteredRaster && last - first < minimumLength) return false;
        int runStart = -1, runEnd = -1, blank = 0, turns = 0, direction = 0;
        float extreme = Float.NaN;
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            for (int y = first; y <= last; y++) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                int x0 = -1, x1 = -1;
                boolean rule =
                        hasRuleFlanks
                                && dark(gray, width, ruleLeft, y, quarterBudget)
                                && dark(gray, width, ruleRight, y, quarterBudget);
                if (rule) continue;
                // The original loop charges for every pixel even after finding both edges.
                // Group those charges only when the local budget can pay the entire row.
                if (unmeteredRaster || (quarterBudget != null && completeRaster && left <= right
                        && quarterBudget.tryPrepayLocal(rowWork))) {
                    int row = y * width;
                    int firstInk = left;
                    while (firstInk <= right && (gray[row + firstInk] & 255) >= 150) firstInk++;
                    if (firstInk <= right) {
                        x0 = firstInk;
                        x1 = right;
                        while (x1 > x0 && (gray[row + x1] & 255) >= 150) x1--;
                    }
                } else {
                    if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                    for (int x = left; x <= right; x++) {
                        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                        if (dark(gray, width, x, y, quarterBudget)) {
                            if (x0 < 0) x0 = x;
                            x1 = x;
                        }
                    }
                }

                if (x0 < 0) {
                    if (++blank > blankLimit) {
                        if (valid(runStart, runEnd, turns, top, bottom, gap, quarterBudget))
                            return true;
                        if (unmeteredRaster && y > latestStart) return false;
                        runStart = -1;
                        runEnd = -1;
                        turns = 0;
                        direction = 0;
                        extreme = Float.NaN;
                    }
                    continue;
                }
                blank = 0;
                if (runStart < 0) runStart = y;
                runEnd = y;
                float center = (x0 + x1) * .5f;
                if (Float.isNaN(extreme)) {
                    extreme = center;
                    continue;
                }
                if (direction == 0) {
                    if (Math.abs(center - extreme) >= excursion) {
                        direction = center > extreme ? 1 : -1;
                        extreme = center;
                    }
                } else if (direction * (center - extreme) >= 0) extreme = center;
                else if (Math.abs(center - extreme) >= excursion) {
                    turns++;
                    direction = -direction;
                    extreme = center;
                }
                if (unmeteredRaster
                        && turns >= 5
                        && runStart <= latestStart
                        && runEnd >= earliestEnd
                        && runEnd - runStart >= minimumLength) return true;
            }
        }

        return valid(runStart, runEnd, turns, top, bottom, gap, quarterBudget);
    }

    private static boolean valid(int first, int last, int turns, int top, int bottom, float gap) {
        return valid(first, last, turns, top, bottom, gap, null);
    }

    private static boolean valid(
            int first,
            int last,
            int turns,
            int top,
            int bottom,
            float gap,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        return first >= 0
                && first <= top + gap * .3f
                && last >= bottom - gap * .3f
                && last - first >= gap * 3.7f
                && turns >= 5;
    }

    private static boolean dark(byte[] gray, int width, int x, int y) {
        return dark(gray, width, x, y, null);
    }

    private static boolean dark(
            byte[] gray, int width, int x, int y, FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        return (gray[y * width + x] & 255) < 150;
    }
}
