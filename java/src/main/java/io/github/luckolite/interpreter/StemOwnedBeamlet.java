// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** A short straight beamlet rooted on a shaft and parallel to its continuing primary beam. */
final class StemOwnedBeamlet {
    private StemOwnedBeamlet() {}

    static boolean proves(
            byte[] gray, int width, int height, int[] shaft, float gap) {
        if (gap < 8 || shaft == null) return false;
        int x = shaft[0], tip = shaft[1], direction = shaft[2];
        for (int side : new int[] {-1, 1}) {
            float[] first = bands(gray, width, height, x + side * Math.round(gap * .24f),
                    tip, direction, gap);
            float[] middle = bands(gray, width, height, x + side * Math.round(gap * .42f),
                    tip, direction, gap);
            float[] last = bands(gray, width, height, x + side * Math.round(gap * .58f),
                    tip, direction, gap);
            if (first.length != 2 || middle.length != 2 || last.length != 2) continue;
            float separation = first[1] - first[0];
            if (separation < gap * .56f || separation > gap * .95f
                    || Math.abs(last[1] - last[0] - separation) > gap * .13f
                    || Math.abs(middle[1] - middle[0] - separation) > gap * .13f
                    || Math.abs(last[0] - first[0]) > gap * .23f
                    || Math.abs(middle[0] - (first[0] + last[0]) * .5f) > gap * .10f)
                continue;
            float[] continuation = bands(gray, width, height,
                    x + side * Math.round(gap * 1.05f), tip, direction, gap);
            float[] opposite = bands(gray, width, height,
                    x - side * Math.round(gap * .58f), tip, direction, gap);
            if (continuation.length != 1 || opposite.length != 1
                    || Math.abs(continuation[0] - last[0]) > gap * .28f
                    || Math.abs(opposite[0] - first[0]) > gap * .48f) continue;

            // The inside core must meet this shaft. Reject a white break or a neighboring flag.
            float root = first[1] - (last[1] - first[1]) * (.24f / .34f);
            int distance = Math.round(root);
            boolean attached = true;
            for (int dx = 0; dx <= Math.round(gap * .24f); dx++) {
                int sample = Math.round(root + (first[1] - root) * dx / (gap * .24f));
                if (!ink(gray, width, height, x + side * dx, tip - direction * sample)
                        || !ink(gray, width, height, x, tip - direction * distance--)) {
                    attached = false;
                    break;
                }
            }
            if (attached) return true;
        }
        return false;
    }

    private static float[] bands(
            byte[] gray, int width, int height, int x, int tip, int direction, float gap) {
        int low = Math.round(-gap * .6f), high = Math.round(gap * 1.7f);
        int start = low;
        float[] centers = new float[2];
        int count = 0;
        for (int d = low; d <= high + 1; d++) {
            if (d <= high && ink(gray, width, height, x, tip - direction * d)) continue;
            int size = d - start;
            if (start != low && d <= high && size >= gap * .39f && size <= gap * .76f) {
                if (count == centers.length) return new float[0];
                centers[count++] = (start + d - 1) * .5f;
            }
            start = d + 1;
        }
        return java.util.Arrays.copyOf(centers, count);
    }

    private static boolean ink(byte[] gray, int w, int h, int x, int y) {
        return x >= 0 && x < w && y >= 0 && y < h && (gray[y * w + x] & 255) < 155;
    }
}
