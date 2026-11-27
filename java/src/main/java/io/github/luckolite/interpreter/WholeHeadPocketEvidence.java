// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** A whole oval has an upright, forward-leaning enclosed opening. */
final class WholeHeadPocketEvidence {
    static boolean proved(
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            float gap) {
        if (gray == null
                || width < 1
                || height < 1
                || gray.length != (long) width * height
                || !Float.isFinite(gap)
                || gap < 6
                || left < 0
                || top < 0
                || right >= width
                || bottom >= height
                || right < left
                || bottom < top) return false;
        int w = right - left + 1, h = bottom - top + 1;
        if (w < gap * 1.35f || w > gap * 2.4f || h < gap * .65f || h > gap * 1.4f) return false;
        return pocket(gray, width, left, top, w, h, gap, 170, false)
                && pocket(gray, width, left, top, w, h, gap, 205, false);
    }

    /** A short ledger can split the opening of a whole oval into two small pockets. */
    static boolean shortLedgerPocket(
            byte[] gray, int width, int height, int left, int top, int right, int bottom,
            float centerX, float centerY, float gap) {
        int w = right - left + 1, h = bottom - top + 1;
        if (gray == null || gray.length != (long) width * height || gap < 8
                || w < gap * 1.35f || w > gap * 2.20f
                || h < gap * .70f || h > gap * 1.50f
                || top < 1 || bottom >= height - 1) return false;
        int reach = Math.max(2, Math.round(gap * .22f));
        int blank = Math.round(gap * .80f);
        if (left < blank || right + blank >= width) return false;
        int first = -1, last = -1;
        int center = Math.round(centerY);
        for (int y = Math.max(top + 1, center - Math.round(gap * .30f));
                y <= Math.min(bottom - 1, center + Math.round(gap * .30f)); y++) {
            int at = y * width;
            if ((gray[at + left - blank] & 255) < 185
                    || (gray[at + right + blank] & 255) < 185) continue;
            int crossing = 0;
            for (int x = left - reach; x <= right + reach; x++)
                if ((gray[at + x] & 255) < 170) crossing++;
            if (crossing < (w + 2 * reach) * .90f) continue;
            if (first < 0) first = y;
            last = y;
        }
        if (first < 0 || last - first + 1 > gap * .36f
                || first < top + h * .24f || last > bottom - h * .24f) return false;

        // Both sides of the crossing need aperture ink contrast between the
        // oval walls. At least one aperture must still have a paper-bright row.
        int x0 = Math.max(left + reach, Math.round(centerX - gap * .32f));
        int x1 = Math.min(right - reach, Math.round(centerX + gap * .32f));
        int upper = 0, lower = 0, bright = 0;
        for (int y = top + 2; y <= bottom - 2; y++) {
            if (y >= first && y <= last) continue;
            int at = y * width;
            if ((gray[at + left - reach] & 255) < 185
                    || (gray[at + right + reach] & 255) < 185) continue;
            int run = 0, longest = 0, paper = 0;
            for (int x = x0; x <= x1; x++) {
                if ((gray[at + x] & 255) >= 178) {
                    longest = Math.max(longest, ++run);
                    if ((gray[at + x] & 255) >= 205) paper++;
                } else run = 0;
            }
            if (longest < Math.max(3, Math.round(gap * .24f))) continue;
            boolean leftWall = false, rightWall = false;
            for (int x = left; x < x0; x++) leftWall |= (gray[at + x] & 255) < 115;
            for (int x = x1 + 1; x <= right; x++) rightWall |= (gray[at + x] & 255) < 115;
            if (!leftWall || !rightWall) continue;
            if (y < first) upper++; else lower++;
            if (paper >= Math.max(2, Math.round(gap * .18f))) bright++;
        }
        return upper >= 1 && lower >= 1 && upper + lower >= 3 && bright >= 1
                && hasEnclosedOpening(gray, width, height, left, top, right, bottom, centerX, centerY, gap);
    }

    /**
     * Refuses positive filled-body authority only for a real enclosed raw-light island
     * inside the proposed body bounds and central ellipse. External gliss pockets are outside
     * this body-local domain and cannot borrow the notehead's own shaft evidence.
     */
    static boolean hasEnclosedOpening(
            byte[] gray, int width, int height, int left, int top, int right, int bottom,
            float centerX, float centerY, float gap) {
        return hasEnclosedOpening(gray, width, height, left, top, right, bottom,
                centerX, centerY, gap, 4, 2);
    }

    static boolean hasEnclosedOpening(
            byte[] gray, int width, int height, int left, int top, int right, int bottom,
            float centerX, float centerY, float gap, int minimumArea, int minimumRows) {
        if (gray == null || width < 1 || height < 1 || gray.length != (long) width * height
                || !Float.isFinite(gap) || gap < 6 || !Float.isFinite(centerX) || !Float.isFinite(centerY)
                || left < 0 || top < 0 || right >= width || bottom >= height || right < left || bottom < top) return true;
        int w = right - left + 1, h = bottom - top + 1;
        long area = (long) w * h;
        if (area <= 0 || area > 32_768 || area * 6L > 200_000L) return true;
        boolean[] seen = new boolean[(int) area];
        int[] queue = new int[(int) area];
        float rx = Math.max(1f, w * .5f), ry = Math.max(1f, h * .5f);
        int[] neighborDeltas = {-1, 1, -w, w};
        for (int seed = 0; seed < area; seed++) {
            if (seen[seed] || (gray[(top + seed / w) * width + left + seed % w] & 255) < 205) continue;
            int read = 0, end = 1, minX = w, minY = h, maxX = -1, maxY = -1;
            long sumX = 0, sumY = 0;
            queue[0] = seed;
            seen[seed] = true;
            boolean edge = false;
            while (read < end) {
                int p = queue[read++], x = p % w, y = p / w;
                edge |= x == 0 || x == w - 1 || y == 0 || y == h - 1;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                sumX += left + x; sumY += top + y;
                // Reuse the bounded neighborhood offsets for every visited opening pixel.
                for (int d : neighborDeltas) {
                    int n = p + d;
                    if (n < 0 || n >= area || Math.abs(n % w - x) + Math.abs(n / w - y) != 1 || seen[n]) continue;
                    if ((gray[(top + n / w) * width + left + n % w] & 255) >= 205) {
                        seen[n] = true;
                        queue[end++] = n;
                    }
                }
            }
            if (edge || end < minimumArea || maxX - minX < 1 || maxY - minY + 1 < minimumRows) continue;
            double mx = sumX / (double) end, my = sumY / (double) end;
            double nx = (mx - centerX) / rx, ny = (my - centerY) / ry;
            if (nx * nx + ny * ny <= .70) return true;
        }
        return false;
    }
    static boolean slantedHalf(
            byte[] gray, int width, int height, int left, int top, int right, int bottom, float gap) {
        int w = right - left + 1, h = bottom - top + 1;
        if (gray == null || gray.length != (long) width * height
                || !Float.isFinite(gap) || gap < 6 || gap > 40
                || left < 0 || top < 0 || right >= width || bottom >= height
                || w < gap * .95f || w > gap * 1.4f || h < gap * .65f || h > gap * 1.35f)
            return false;
        return pocket(gray, width, left, top, w, h, gap, 170, true)
                && pocket(gray, width, left, top, w, h, gap, 205, true);
    }

    private static boolean pocket(
            byte[] gray, int stride, int left, int top, int w, int h, float gap, int threshold, boolean slantedHalf) {
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        int total = 0, parts = 0;
        for (int seed = 0; seed < w * h; seed++) {
            if (seen[seed] || (gray[(top + seed / w) * stride + left + seed % w] & 255) < threshold)
                continue;
            int read = 0, end = 1;
            queue[0] = seed;
            seen[seed] = true;
            boolean edge = false;
            while (read < end) {
                int p = queue[read++], x = p % w, y = p / w;
                edge |= x == 0 || x == w - 1 || y == 0 || y == h - 1;
                for (int delta : new int[] {-1, 1, -w, w}) {
                    int n = p + delta;
                    if (n < 0
                            || n >= w * h
                            || Math.abs(n % w - x) + Math.abs(n / w - y) != 1
                            || seen[n]) continue;
                    if ((gray[(top + n / w) * stride + left + n % w] & 255) >= threshold) {
                        seen[n] = true;
                        queue[end++] = n;
                    }
                }
            }
            if (edge || end < Math.max(2, gap * gap * .025f)) continue;
            parts++;
            for (int i = 0; i < end; i++) {
                int x = queue[i] % w, y = queue[i] / w;
                total++;
                sx += x;
                sy += y;
                sxx += x * x;
                syy += y * y;
                sxy += x * y;
            }
        }
        if (parts < 1 || parts > 3 || total < gap * gap * .12f || total > gap * gap * .9f)
            return false;
        double mx = sx / total,
                my = sy / total,
                vx = sxx / total - mx * mx,
                vy = syy / total - my * my,
                cov = sxy / total - mx * my;
        return mx > w * .25
                && mx < w * .75
                && my > h * .2
                && my < h * .8
                && vx > 0
                && (slantedHalf
                        ? vy >= vx * .32 && vy <= vx * 1.25 && cov <= -Math.sqrt(vx * vy) * .45
                        : vy >= vx * .7 && cov > Math.sqrt(vx * vy) * .12);
    }
}




