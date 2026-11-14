// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** A letter's arch continues into outward stems; a tie's interior has a thin detached contour. */
final class TieArcBranchInk {
    private TieArcBranchInk() {}

    static boolean outwardStems(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            float[] centers,
            int side,
            float gap,
            int limit) {
        int lastX = -1, branches = 0;
        int radius = Math.max(1, Math.round(gap * .15f));
        float requiredBranch = gap * .75f;
        float requiredRule = gap * 3.5f;
        float ruleReach = gap * 5;
        for (int sample = centers.length / 5; sample < centers.length * 4 / 5; sample++) {
            if (!Float.isFinite(centers[sample])) continue;
            int x = Math.round(left + (sample + .5f) * (right - left) / centers.length);
            if (x == lastX || x < 0 || x >= width) continue;
            lastX = x;
            int start = Math.round(centers[sample]), ink = 0, blanks = 0;
            int first = Math.max(0, x - radius), last = Math.min(width - 1, x + radius);
            boolean rule = false;
            for (int xx = first; xx <= last; xx++)
                if (hasVerticalRule(gray, width, height, xx, start, ruleReach, requiredRule, limit)) {
                    rule = true;
                    break;
                }
            if (rule) continue;
            for (int distance = 1; distance <= gap; distance++) {
                int y = start + side * distance;
                if (y < 0 || y >= height) break;
                boolean present = false;
                int row = y * width;
                for (int at = row + first, end = row + last; at <= end; at++)
                    if ((gray[at] & 255) <= limit) {
                        present = true;
                        break;
                    }
                if (present) {
                    if (++ink >= requiredBranch) break;
                    blanks = 0;
                } else if (++blanks > 1) break;
            }
            if (ink >= requiredBranch && ++branches >= 3) return true;
        }
        return false;
    }

    private static boolean hasVerticalRule(
            byte[] gray, int width, int height, int x, int start, float reach, float minimum, int limit) {
        int span = 0;
        for (int side = -1; side <= 1; side += 2) {
            int blanks = 0;
            for (int distance = 0; distance <= reach; distance++) {
                int y = start + side * distance;
                if (y < 0 || y >= height) break;
                if ((gray[y * width + x] & 255) <= limit) {
                    if (++span >= minimum) return true;
                    blanks = 0;
                } else if (++blanks > 1) break;
            }
        }
        return span >= minimum;
    }
}
