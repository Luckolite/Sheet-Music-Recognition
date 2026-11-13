// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Separate printed beam bands cannot establish an inter-staff ink connection. */
final class BeamInkConnectivity {
    private BeamInkConnectivity() {}

    static boolean connected(
            byte[] gray, int width, int height, int ax, int ay, int bx, int by, float gap) {
        return connected(gray, width, height, ax, ay, bx, by, gap, null);
    }

    static boolean connected(
            byte[] gray,
            int width,
            int height,
            int ax,
            int ay,
            int bx,
            int by,
            float gap,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        if (gray == null || gray.length != (long) width * height || bx <= ax || gap < 2)
            return false;
        int left = ax + 2, right = bx - 2, radius = Math.max(2, Math.round(gap * .22f));
        int top = Math.max(0, Math.min(ay, by) - radius),
                bottom = Math.min(height - 1, Math.max(ay, by) + radius);
        if (left < 0 || right >= width || right <= left) return false;
        int first = lineAt(left, ax, ay, bx, by);
        int last = lineAt(right, ax, ay, bx, by);
        if (quarterBudget == null) {
            if (!hasEndpoint(gray, width, left, first, top, bottom)
                    || !hasEndpoint(gray, width, right, last, top, bottom)) return false;
            if (followsCenterline(gray, width, left, right, top, bottom, ax, ay, bx, by, first))
                return true;
        }

        int w = right - left + 1, h = bottom - top + 1;
        boolean[] seen =
                (quarterBudget == null
                                || quarterBudget.allocate(
                                        (Math.max(0L, (long) (w * h))) + 1,
                                        32L * 1 + 8L * (Math.max(0L, (long) (w * h))))
                        ? new boolean[w * h]
                        : null);
        int[] queue =
                (quarterBudget == null
                                || quarterBudget.allocate(
                                        (Math.max(0L, (long) (w * h))) + 1,
                                        32L * 1 + 8L * (Math.max(0L, (long) (w * h))))
                        ? new int[w * h]
                        : null);
        int[] centerline = null;
        if (quarterBudget == null) {
            centerline = new int[w];
            for (int i = 0; i < w; i++) centerline[i] = lineAt(left + i, ax, ay, bx, by);
        }
        int size = 0, take = 0;
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            for (int y = Math.max(top, first - 1); y <= Math.min(bottom, first + 1); y++) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                if ((gray[y * width + left] & 255) < 165) {
                    int at = (y - top) * w;
                    seen[at] = true;
                    queue[size++] = at;
                }
            }
        }

        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            while (take < size) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                int at = queue[take++], x = left + at % w, y = top + at / w;
                if (x == right && Math.abs(y - last) <= 1) return true;
                {
                    if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                    for (int dy = -1; dy <= 1; dy++) {
                        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                        {
                            if (quarterBudget != null)
                                FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                            for (int dx = -1; dx <= 1; dx++) {
                                if (quarterBudget != null)
                                    FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                                if (quarterBudget == null && dx == 0 && dy == 0) continue;
                                int nx = x + dx, ny = y + dy;
                                if (nx < left || nx > right || ny < top || ny > bottom) continue;
                                int next = at + dy * w + dx;
                                if (seen[next]) continue;
                                // Rejected pixels cannot become eligible from a different parent.
                                if (quarterBudget == null) seen[next] = true;
                                int line = centerline == null
                                        ? lineAt(nx, ax, ay, bx, by) : centerline[nx - left];
                                if (Math.abs(ny - line) > radius
                                        || (gray[ny * width + nx] & 255) >= 165) continue;
                                seen[next] = true;
                                queue[size++] = next;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private static int lineAt(int x, int ax, int ay, int bx, int by) {
        return Math.round(ay + (by - ay) * (x - ax) / (float) (bx - ax));
    }

    private static boolean hasEndpoint(
            byte[] gray, int width, int x, int center, int top, int bottom) {
        for (int y = Math.max(top, center - 1); y <= Math.min(bottom, center + 1); y++) {
            if ((gray[y * width + x] & 255) < 165) return true;
        }
        return false;
    }

    private static boolean followsCenterline(
            byte[] gray, int width, int left, int right, int top, int bottom,
            int ax, int ay, int bx, int by, int first) {
        int previous = first;
        for (int x = left; x <= right; x++) {
            int y = lineAt(x, ax, ay, bx, by);
            if (y < top || y > bottom || Math.abs(y - previous) > 1
                    || (gray[y * width + x] & 255) >= 165) return false;
            previous = y;
        }
        return true;
    }
}
