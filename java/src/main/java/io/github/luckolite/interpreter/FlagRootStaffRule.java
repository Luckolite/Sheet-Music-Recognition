// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Reject horizontal staff-rule edges as evidence that a pale flag touches its stem. */
final class FlagRootStaffRule {
    static boolean matches(
            byte[] gray, int width, int height, int y, int stemX, float gap, int threshold) {
        if (gray == null || gap < 4) return false;
        int radius = Math.max(1, (int) Math.floor(gap * .08f));
        int near = Math.round(gap * 2), far = Math.round(gap * 4);
        int firstRow = Math.max(0, y - radius), lastRow = Math.min(height - 1, y + radius);
        int minimumSamples = Math.max(6, Math.round(gap));
        for (int direction = -1; direction <= 1; direction += 2) {
            long start = Math.max(0, direction < 0 ? (long) stemX - far : (long) stemX + near);
            long end = Math.min(width - 1L, direction < 0 ? (long) stemX - near : (long) stemX + far);
            if (end - start + 1 < minimumSamples) return false;
            float required = (end - start + 1) * .85f;
            int hits = 0;
            for (int x = (int) start; x <= (int) end; x++) {
                for (int row = firstRow, pixel = firstRow * width + x;
                        row <= lastRow; row++, pixel += width) {
                    if ((gray[pixel] & 255) > threshold) continue;
                    hits++;
                    break;
                }
                if (hits >= required) break;
                if (hits + ((int) end - x) < required) return false;
            }
        }
        return true;
    }
}
