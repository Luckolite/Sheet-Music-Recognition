// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Five raw rules need bilateral contrast and consistent phase, independent of absolute exposure. */
final class FaintDirectionRails {
    private static final float[] SLOPES = {0, .04f, -.04f, .08f, -.08f, .12f, -.12f, .16f, -.16f};

    static boolean valid(byte[] gray, int width, int height, float x, float hint, float gap) {
        return gray != null
                && width > 0
                && height > 0
                && gray.length == (long) width * height
                && Float.isFinite(x)
                && x >= 0
                && x < width
                && Float.isFinite(hint)
                && hint >= 0
                && hint < height
                && Float.isFinite(gap)
                && gap >= 6
                && gap <= Math.min(width, height) / 8f;
    }

    static float[] read(
            byte[] gray, int width, int height, float x, float hint, float gap, float radiusGaps) {
        if (!valid(gray, width, height, x, hint, gap)
                || !Float.isFinite(radiusGaps)
                || radiusGaps < 3.5f
                || radiusGaps > 5.5f) return null;
        int radius = Math.round(gap * radiusGaps), center = Math.round(x);
        int left = center - radius, right = center + radius;
        int top = (int) Math.floor(hint - gap * 5.5f), bottom = (int) Math.ceil(hint + gap * 1.5f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        int w = right - left + 1,
                h = bottom - top + 1,
                exclusion = Math.max(2, Math.round(gap * .5f));
        float[] accepted = null;
        byte[] crop = new byte[w * h];
        int[] offsets = new int[w];
        int[] rowCounts = new int[h];
        float[] shifts = {0, -gap * .2f, gap * .2f};
        int flank = Math.max(2, Math.round(gap * .3f));
        int band = Math.max(1, Math.round(gap * .2f));
        int flankStride = flank * w;
        for (float slope : SLOPES) {
            boolean valid = true;
            if (slope == 0) {
                for (int row = 0; row < h; row++)
                    System.arraycopy(gray, (top + row) * width + left, crop, row * w, w);
            } else {
                for (int xx = left; xx <= right; xx++) {
                    int dy = Math.round((xx - x) * slope);
                    if (top + dy < 0 || bottom + dy >= height) {
                        valid = false;
                        break;
                    }
                    offsets[xx - left] = (top + dy) * width + xx;
                }
                if (valid) {
                    for (int row = 0, dest = 0; row < h; row++, dest += w) {
                        int sourceOffset = row * width;
                        for (int col = 0; col < w; col++)
                            crop[dest + col] = gray[offsets[col] + sourceOffset];
                    }
                }
            }
            if (!valid) continue;
            java.util.Arrays.fill(rowCounts, 0);
            // Written-head floors inherit a small staff-gap rounding error at remote steps.
            // Every allowed reference offset must still converge on the same complete raw rails.
            for (float shift : shifts) {
                var found = StaffPitchTrack.broadStraightPitch(
                        crop, w, h, hint - top + shift, gap, false, rowCounts, 255, 12);
                if (found == null) continue;
                boolean bilateral = true;
                int samples = radius - exclusion;
                for (int line = 0; line < 5 && bilateral; line++) {
                    int centerY = Math.round(found[0] - line * found[1]);
                    int from = Math.max(flank, centerY - band) * w;
                    int to = Math.min(h - 1 - flank, centerY + band) * w;
                    for (int side = -1; side <= 1; side += 2) {
                        int hits = 0;
                        for (int dx = exclusion + 1; dx <= radius; dx++) {
                            int column = center + side * dx - left;
                            for (int at = from + column; at <= to + column; at += w) {
                                int tone = crop[at] & 255;
                                if ((crop[at - flankStride] & 255) > tone + 12
                                        && (crop[at + flankStride] & 255) > tone + 12) {
                                    hits++;
                                    break;
                                }
                            }
                        }
                        if (samples < 8 || hits < samples * .6f) {
                            bilateral = false;
                            break;
                        }
                    }
                }
                if (!bilateral) continue;
                found[0] += top;
                if (accepted != null
                        && (Math.abs(accepted[0] - found[0]) > gap * .2f
                                || Math.abs(accepted[1] - found[1]) > gap * .08f)) return null;
                accepted = found;
            }
        }
        return accepted;
    }

    static float[] broadStraightPitch(
            byte[] gray, int width, int height, float bottom, float gap, boolean correctionOnly) {
        return StaffPitchTrack.broadStraightPitch(
                gray, width, height, bottom, gap, correctionOnly, null, 255, 12);
    }
}
