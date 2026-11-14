// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** An unbroken pair of rails meets all three independently owned downstems. */
final class LinkedFilledThirds {
    private LinkedFilledThirds() {}

    static boolean joined(byte[] gray, int width, float gap, int[] a, int[] b) {
        return joined(gray, width, gap, a, b, false);
    }

    static boolean ends(byte[] gray, int width, float gap, int[] a, int[] b) {
        return joined(gray, width, gap, a, b, true);
    }

    private static boolean joined(byte[] gray, int width, float gap, int[] a, int[] b, boolean closing) {
        int span = b[0] - a[0];
        if (span < gap * 1.8f || span > gap * 3.15f
                || Math.abs(a[1] - b[1]) > span * .29f) return false;
        int margin = Math.round(gap * .34f);
        int first = a[0] + margin, last = b[0] - margin;
        int radius = Math.max(2, Math.round(gap * .12f));
        for (int outer = Math.round(gap * .16f); outer <= gap * .44f; outer++) {
            for (int separation = Math.round(gap * .62f); separation <= gap; separation++) {
                int paired = 0, paper = 0;
                for (int x = first; x <= last; x++) {
                    float projected = a[1] + (b[1] - a[1]) * (x - a[0]) / (float) span;
                    if (thick(gray, width, x, Math.round(projected - outer), radius)
                            && thick(gray, width, x, Math.round(projected - outer - separation), radius))
                        paired++;
                    if (tone(gray, width, x, Math.round(projected - outer - .5f * separation)) > 170)
                        paper++;
                }
                int attempted = last - first + 1;
                if (paired >= attempted * .94f && paper >= attempted * .35f
                        && (!closing || clearsEnd(gray, width, gap, a, b, outer, separation, radius)))
                    return true;
            }
        }
        return false;
    }

    private static boolean clearsEnd(byte[] gray, int width, float gap, int[] a, int[] b,
            int outer, int separation, int radius) {
        int count = 0, clear = 0;
        for (int x = b[0] + Math.round(gap * .30f); x < b[0] + Math.round(gap * 1.36f); x++) {
            float y = b[1] + (b[1] - a[1]) * (x - b[0]) / (float) (b[0] - a[0]);
            count++;
            if (!thick(gray, width, x, Math.round(y - outer), radius)
                    && !thick(gray, width, x, Math.round(y - outer - separation), radius)) clear++;
        }
        return clear >= count * .90f;
    }
    private static boolean thick(byte[] gray, int width, int x, int y, int radius) {
        for (int row = y - radius; row <= y + radius; row++)
            if (tone(gray, width, x, row) >= 135) return false;
        return true;
    }

    private static int tone(byte[] gray, int width, int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= gray.length / width) return 255;
        return gray[y * width + x] & 255;
    }
}
