// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Two printed rails that close at a dyad shaft, including overprinted staff ink. */
final class InnerBeamShaftEnd {
    private InnerBeamShaftEnd() {}

    static boolean proves(byte[] raw, int width, float gap, int[] mask,
            int[] earlier, int[] previous, int[] last) {
        return proves(raw, width, gap, mask, earlier, previous, last, .3f, 2f);
    }

    /** The inner band can be near a staff line that lengthens the detected tip. */
    static boolean provesCompactOctaveEndpoint(
            byte[] raw, int width, float gap, int[] mask,
            int[] earlier, int[] previous, int[] last) {
        int min = Math.max(1, Math.round(gap * .12f));
        int max = Math.max(min, Math.round(gap * .23f));
        for (int excess = min; excess <= max; excess++) {
            int[] end = {last[0], last[1] - excess, last[2]};
            if (proves(raw, width, gap, mask, earlier, previous, end)) return true;
        }
        return false;
    }

    static boolean provesLedgerEndpoint(
            byte[] raw,
            int width,
            float gap,
            int[] mask,
            int[] earlier,
            int[] previous,
            int[] last) {
        return proves(raw, width, gap, mask, earlier, previous, last, .33f, 2f);
    }

    static boolean provesSmallStaffTip(
            byte[] raw, int width, float gap, int[] mask, int[] earlier, int[] previous, int[] last) {
        return proves(raw, width, gap, mask, earlier, previous, last, .30f, 1.875f);
    }


    private static boolean proves(
            byte[] raw,
            int width,
            float gap,
            int[] mask,
            int[] earlier,
            int[] previous,
            int[] last,
            float rightOverhang, float minimumShaftGap) {
        int padding = Math.max(2, Math.round(gap * .34f));
        int radius = Math.max(2, Math.round(gap * .14f));
        int x0 = earlier[0], x1 = previous[0], x2 = last[0];
        if (x1 - x0 < gap * minimumShaftGap || x1 - x0 > gap * 4
                || x2 - x1 < gap * minimumShaftGap || x2 - x1 > gap * 4
                || Math.abs(last[1] - previous[1]) > (x2 - x1) * .29f
                || Math.abs(previous[1] - earlier[1]) > (x1 - x0) * .29f) return false;

        int dark = 0;
        for (int pixel : mask) {
            int x = pixel % width, y = pixel / width;
            float depth = tip(previous, last, x) - y;
            if (x < x2 - gap * 1.4f
                    || x > x2 + gap * rightOverhang
                    || depth < -gap * .10f
                    || depth > gap * 1.35f) return false;
            if ((raw[pixel] & 255) < 150) dark++;
        }
        if (dark < mask.length * .93f) return false;

        for (int outer = Math.round(gap * .17f); outer <= gap * .47f; outer++) {
            for (int separation = Math.round(gap * .65f); separation <= gap * 1.05f; separation++) {
                int inner = outer + separation;
                if (!paired(raw, width, earlier, previous, padding, radius, outer, inner, true)
                        || !paired(raw, width, previous, last, padding, radius, outer, inner, false))
                    continue;
                int pixels = 0, continuing = 0;
                for (int x = x2 + padding; x <= x2 + Math.round(gap * 1.42f); x++) {
                    pixels++;
                    float end = tip(previous, last, x);
                    if (thick(raw, width, x, end - outer, radius)
                            || thick(raw, width, x, end - inner, radius)) continuing++;
                }
                if (continuing <= pixels * .15f) return true;
            }
        }
        return false;
    }

    private static boolean paired(byte[] raw, int width, int[] left, int[] right,
            int padding, int radius, int outer, int inner, boolean requireSeparator) {
        int total = 0, paired = 0, blank = 0;
        for (int x = left[0] + padding; x <= right[0] - padding; x++) {
            float end = tip(left, right, x);
            total++;
            if (thick(raw, width, x, end - outer, radius)
                    && thick(raw, width, x, end - inner, radius)) paired++;
            if (tone(raw, width, x, Math.round(end - (outer + inner) * .5f)) > 170) blank++;
        }
        return paired >= total * .91f && (!requireSeparator || blank >= total * .43f);
    }

    private static float tip(int[] left, int[] right, int x) {
        return left[1] + (right[1] - left[1]) * (x - left[0]) / (float) (right[0] - left[0]);
    }

    private static boolean thick(byte[] raw, int width, int x, float center, int radius) {
        int mid = Math.round(center);
        for (int y = mid - radius; y <= mid + radius; y++)
            if (tone(raw, width, x, y) >= 135) return false;
        return true;
    }

    private static int tone(byte[] raw, int width, int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= raw.length / width) return 255;
        return raw[y * width + x] & 255;
    }
}
