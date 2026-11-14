// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Three independent aligned cores across a slightly wider full-size beam pair. */
final class WideTripleBeamInk {
    private WideTripleBeamInk() {}

    static int count(byte[] gray, int w, int h, int[] a, int[] b, float gap) {
        return count(gray, w, h, a, b, gap, 3);
    }

    /** Four complete aligned cores, proved away from each attached shaft. */
    static int countFour(byte[] gray, int w, int h, int[] a, int[] b, float gap) {
        return count(gray, w, h, a, b, gap, 4);
    }

    private static int count(byte[] gray, int w, int h, int[] a, int[] b, float gap, int expected) {
        if (gray == null
                || w < 3
                || h < 3
                || gray.length != (long) w * h
                || !Float.isFinite(gap)
                || gap < 8
                || a == null
                || b == null
                || a.length < 3
                || b.length < 3
                || Math.abs(a[2]) != 1
                || a[2] != b[2]
                || a[0] < 1
                || a[0] >= w - 1
                || b[0] < 1
                || b[0] >= w - 1
                || a[1] < 1
                || a[1] >= h - 1
                || b[1] < 1
                || b[1] >= h - 1) return 0;
        int span = Math.abs(a[0] - b[0]);
        if (span < gap * (expected == 4 ? 1.2f : 3)
                || span > gap * 4
                || Math.abs(a[1] - b[1]) > gap * .75f) return 0;
        int[] thresholds = new int[3], darkestTones = new int[3];
        int prepared = 0;
        float inside = expected == 4 ? 3.4f : 2.4f;
        int minimumCore = Math.max(3, (int) Math.ceil(gap * .3f));
        float maximumCore = gap * .8f;
        float[] previous = null, current = null;
        for (int cutoff = 2; cutoff <= 3; cutoff++) {
            float fraction = cutoff * .25f;
            boolean okay = true, retryCanDiffer = false;
            for (int column = 0; column < 3; column++) {
                float f = (column + 1) * .25f;
                int x = Math.round(a[0] + (b[0] - a[0]) * f);
                float end = a[1] + (b[1] - a[1]) * f;
                int top = Math.max(1, Math.round(end - (a[2] < 0 ? .35f : inside) * gap));
                int bottom = Math.min(h - 2, Math.round(end + (a[2] < 0 ? inside : .35f) * gap));
                if (column == prepared) {
                    int threshold = BeamInkThreshold.at(gray, w, h, x, top, bottom, gap);
                    int darkest = threshold;
                    for (int y = top, pixel = top * w + x; y <= bottom; y++, pixel += w)
                        darkest = Math.min(darkest, gray[pixel] & 255);
                    thresholds[column] = threshold;
                    darkestTones[column] = darkest;
                    prepared++;
                }
                int darkest = darkestTones[column];
                int range = thresholds[column] - darkest;
                int threshold = darkest + Math.round(range * fraction);
                if (cutoff == 2 && threshold != darkest + Math.round(range * .75f))
                    retryCanDiffer = true;
                int coreCount = 0, start = -1;
                for (int y = top, pixel = top * w + x; y <= bottom + 1; y++, pixel += w) {
                    boolean ink =
                            y <= bottom
                                    && (gray[pixel - 1] & 255) < threshold
                                    && (gray[pixel] & 255) < threshold
                                    && (gray[pixel + 1] & 255) < threshold;
                    if (ink && start < 0) start = y;
                    if (!ink && start >= 0) {
                        int size = y - start;
                        if (size >= minimumCore && size <= maximumCore) {
                            float core = (start + y - 1) * .5f - end;
                            if (coreCount == expected
                                    || (coreCount > 0 && (core - current[coreCount - 1] < gap * .3f
                                            || core - current[coreCount - 1] > gap * .9f))
                                    || (column > 0 && Math.abs(core - previous[coreCount]) > gap * .22f)) {
                                okay = false;
                                break;
                            }
                            if (current == null) current = new float[expected];
                            current[coreCount++] = core;
                        }
                        start = -1;
                    }
                }
                if (!okay || coreCount != expected) {
                    okay = false;
                    break;
                }
                float[] reusable = previous;
                previous = current;
                current = reusable;
            }
            if (okay) return expected;
            // An identical prefix would fail at the same column under the second cutoff.
            if (cutoff == 2 && !retryCanDiffer) return 0;
        }
        return 0;
    }
}
