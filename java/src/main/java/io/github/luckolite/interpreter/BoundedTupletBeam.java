// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Two continuous beam levels with an inner-level break at both ends of a printed group. */
final class BoundedTupletBeam {
    private BoundedTupletBeam() {}

    static boolean five(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float nextX,
            float numeralTop,
            float numeralBottom,
            float gap,
            boolean above) {
        if (gray == null
                || width < 1
                || height < 1
                || gray.length != width * height
                || gap < 4
                || lastX - firstX < gap * 3
                || nextX - lastX < gap) return false;
        int direction = above ? 1 : -1;
        float center = (firstX + lastX) * .5f;
        int radius = Math.max(1, Math.round(gap * .08f));
        // A stem sits beside the oval. The numeral is centred over the stem-to-stem beam.
        for (float stemOffset : new float[] {gap * .45f, -gap * .45f}) {
            float left = firstX + stemOffset, right = lastX + stemOffset;
            int beamStart = Math.round(left + gap * .15f);
            int beamEnd = Math.round(right - gap * .15f);
            int beforeStart = Math.round(left - gap * .45f);
            int beforeEnd = Math.round(left - gap * .2f);
            int afterStart = Math.round(right + gap * .2f);
            int afterEnd = Math.round(right + gap * .45f);
            float axis = center + stemOffset;
            for (int slopeIndex = -20; slopeIndex <= 20; slopeIndex++) {
                float slope = slopeIndex * .02f;
                for (int distance = Math.round(gap * .3f); distance <= gap * 2; distance++) {
                    float outer = (above ? numeralBottom : numeralTop) + direction * distance;
                    if (!hasCoverage(gray, width, height, beamStart, beamEnd, axis, outer,
                            slope, radius, .94, true)) continue;
                    for (int separation = Math.round(gap * .35f);
                            separation <= gap * .8f;
                            separation++) {
                        float inner = outer + direction * separation;
                        if (!hasCoverage(gray, width, height, beamStart, beamEnd, axis, inner,
                                slope, radius, .94, true)) continue;
                        // A following hook can approach the group. Check the gap just outside each stem.
                        if (hasCoverage(gray, width, height, beforeStart, beforeEnd, axis, inner,
                                        slope, radius, .2, false)
                                && hasCoverage(gray, width, height, afterStart, afterEnd, axis, inner,
                                        slope, radius, .2, false)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasCoverage(
            byte[] gray, int width, int height, int start, int end, float center, float baseline,
            float slope, int radius, double threshold, boolean minimum) {
        // A clipped ray has the original full-coverage value. The rounded ray is monotone.
        if (start > end || start < 0 || end >= width
                || !inBounds(rayY(start, center, baseline, slope), radius, height)
                || !inBounds(rayY(end, center, baseline, slope), radius, height)) return minimum;
        int total = end - start + 1;
        int cutoff = (int) (total * threshold);
        // Keep the original division-based comparison at the rounding boundary.
        if (minimum && cutoff / (double) total < threshold) cutoff++;
        if (!minimum && (cutoff + 1) / (double) total <= threshold) cutoff++;
        int ink = 0;
        for (int x = start; x <= end; x++) {
            int y = rayY(x, center, baseline, slope);
            int d = -radius;
            for (; d <= radius; d++) {
                if ((gray[(y + d) * width + x] & 255) >= 165) break;
            }
            if (d > radius) ink++;
            int possibleInk = ink + (end - x);
            if (minimum) {
                if (ink >= cutoff) return true;
                if (possibleInk < cutoff) return false;
            } else {
                if (ink > cutoff) return false;
                if (possibleInk <= cutoff) return true;
            }
        }
        return minimum;
    }

    private static int rayY(int x, float center, float baseline, float slope) {
        return Math.round(baseline + slope * (x - center));
    }

    private static boolean inBounds(int y, int radius, int height) {
        return y >= radius && y < height - radius;
    }
}
