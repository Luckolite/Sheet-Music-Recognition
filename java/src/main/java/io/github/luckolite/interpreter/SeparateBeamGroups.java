// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Evidence of two separate outward-going beams, not merely a faded connection. */
final class SeparateBeamGroups {
    record Tip(int x, int y, int direction) {}

    static boolean between(
            byte[] g, int w, int h, float ax, float ay, float bx, float by, float gap) {
        if (g == null || gap < 6 || bx - ax < gap * 1.3f || bx - ax > gap * 5) return false;
        int inset = Math.round(gap * .25f), radius = Math.round(gap * .35f);
        int coreRadius = coreRadius(gap);
        for (int direction = -1; direction <= 1; direction += 2) {
            Tip a = tip(g, w, h, ax, ay, gap, direction, -1);
            if (a == null) continue;
            Tip b = tip(g, w, h, bx, by, gap, direction, 1);
            if (b == null || b.x - a.x < gap * .7f) continue;
            int left = a.x + inset, right = b.x - inset;
            int total = right - left + 1;
            if (total < gap * .35f) continue;
            float required = total * .85f;
            int clear = 0;
            for (int x = left; x <= right; x++) {
                if (!hasCore(g, w, h, x, lineAt(a, b, x), radius, coreRadius)
                        && ++clear >= required) return true;
                if (clear + (right - x) < required) break;
            }
        }
        return false;
    }

    static boolean connected(
            byte[] g,
            int w,
            int h,
            float ax,
            float ay,
            float bx,
            float by,
            float gap,
            int direction) {
        Tip a = tip(g, w, h, ax, ay, gap, direction, 1);
        if (a == null) return false;
        Tip b = tip(g, w, h, bx, by, gap, direction, -1);
        if (b == null || b.x - a.x < gap * 2) return false;
        int left = a.x + 2, right = b.x - 2;
        int total = right - left + 1;
        if (total <= 0) return false;
        float required = total * .94f;
        int radius = Math.round(gap * .2f), coreRadius = coreRadius(gap);
        int covered = 0;
        for (int x = left; x <= right; x++) {
            if (hasCore(g, w, h, x, lineAt(a, b, x), radius, coreRadius)
                    && ++covered >= required) return true;
            if (covered + (right - x) < required) return false;
        }
        return false;
    }

    private static int lineAt(Tip a, Tip b, int x) {
        float t = (x - a.x) / (float) (b.x - a.x);
        return Math.round(a.y + t * (b.y - a.y));
    }

    private static boolean hasCore(
            byte[] g, int w, int h, int x, int y, int radius, int coreRadius) {
        for (int dy = -radius; dy <= radius; dy++) {
            if (core(g, w, h, x, y + dy, coreRadius)) return true;
        }
        return false;
    }

    private static Tip tip(
            byte[] g, int w, int h, float hx, float hy, float gap, int direction, int outward) {
        int best = 0, bestX = 0, bestY = 0;
        int headY = Math.round(hy);
        if (headY < 0 || headY >= h || Float.isNaN(gap)) return null;
        int maxDistance = Math.min((int) (gap * 5), direction < 0 ? headY : h - 1 - headY);
        if (maxDistance < gap * 1.8f) return null;
        int firstX = Math.max(0, Math.round(hx - gap * .65f));
        int lastX = Math.min(w - 1, Math.round(hx + gap * .65f));
        for (int x = firstX; x <= lastX && best < maxDistance; x++) {
            int run = 0, blanks = 0;
            for (int d = 0; d <= maxDistance; d++) {
                int y = headY + direction * d;
                if ((g[y * w + x] & 255) < 165) {
                    run++;
                    blanks = 0;
                } else if (++blanks > 1) break;
                if (d < gap * 1.8f || run < d * .85f || d <= best) continue;
                if (outwardBeam(g, w, h, x, y, gap, outward)) {
                    bestX = x;
                    bestY = y;
                    best = d;
                }
            }
        }
        return best == 0 ? null : new Tip(bestX, bestY, direction);
    }

    private static boolean outwardBeam(byte[] g, int w, int h, int x, int y, float gap, int side) {
        int firstD = Math.round(gap * .25f), lastD = Math.round(gap * 1.35f);
        int total = lastD - firstD + 1;
        if (total <= 0) return false;
        float required = total * .94f;
        int radius = coreRadius(gap);
        for (int angle = -10; angle <= 10; angle++) {
            int count = 0;
            float slope = angle * .06f;
            for (int d = firstD; d <= lastD; d++) {
                if (core(g, w, h, x + side * d, Math.round(y + slope * d), radius)
                        && ++count >= required) return true;
                if (count + (lastD - d) < required) break;
            }
        }
        return false;
    }

    private static int coreRadius(float gap) {
        return Math.max(1, Math.round(gap * .12f));
    }

    private static boolean core(byte[] g, int w, int h, int x, int y, int rad) {
        if (x < 0 || x >= w || y - rad < 0 || y + rad >= h) return false;
        for (int dy = -rad; dy <= rad; dy++) if ((g[(y + dy) * w + x] & 255) >= 165) return false;
        return true;
    }
}
