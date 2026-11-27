// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** An elongated ribbon with repeated alternating bends, bound to two consecutive printed heads. */
final class WaveGlissDetector {
    record Link(int sourceIndex, int targetIndex) {}

    private WaveGlissDetector() {}

    static List<Link> detect(
            byte[] gray,
            int width,
            int height,
            List<NoteSlideDetector.Staff> staffs,
            List<NoteSlideDetector.Head> heads) {
        if (gray == null
                || gray.length != (long) width * height
                || heads == null
                || heads.size() < 2) return List.of();
        return detectWithRemovedStaffLines(
                width,
                height,
                heads,
                NoteSlideDetector.removeStaffLines(gray, width, height, staffs));
    }

    /** Consumes an owned raster: flood traversal replaces its ink with white. */
    static List<Link> detectWithRemovedStaffLines(
            int width, int height, List<NoteSlideDetector.Head> heads, byte[] clean) {
        var components = new PackedInkFlood(clean, width, height, 150, 1, 0);
        int[] queue = components.queue;
        List<Link> result = new ArrayList<>();
        int count;
        while ((count = components.next()) != 0) {
            int left = components.left, right = components.right, top = components.top, bottom = components.bottom;
            if (count < 16 || right - left < 8 || bottom - top < 6) continue;
            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            for (int i = 0; i < count; i++) {
                int pixel = queue[i], y = pixel / width, x = pixel - y * width;
                sx += x;
                sy += y;
                sxx += (double) x * x;
                syy += (double) y * y;
                sxy += (double) x * y;
            }
            double cx = sx / count,
                    cy = sy / count,
                    vx = sxx / count - cx * cx,
                    vy = syy / count - cy * cy,
                    cov = sxy / count - cx * cy;
            double angle = .5 * Math.atan2(2 * cov, vx - vy),
                    ux = Math.cos(angle),
                    uy = Math.sin(angle);
            double length = Math.hypot(right - left, bottom - top);
            double residual = Math.sqrt(Math.max(0, (vx + vy - Math.hypot(vx - vy, 2 * cov)) / 2));
            if (Math.abs(ux) < .12 || Math.abs(uy) < .08 || length < 20 || residual > 5) continue;
            int source = -1, target = -1;
            double best = Double.POSITIVE_INFINITY;
            double slope = uy / ux;
            double leftY = cy + (left - cx) * slope, rightY = cy + (right - cx) * slope;
            for (int a = 0; a < heads.size(); a++) {
                var first = heads.get(a);
                float gap = first.gap();
                if (first.staff() < 0
                        || first.x() > left - gap * .35f
                        || left - first.x() > gap * 3
                        || length < gap * 2
                        || length > gap * 12
                        || residual > gap * .28
                        || Math.hypot(left - first.x(), leftY - first.y()) > gap * 3) continue;
                for (int b = 0; b < heads.size(); b++) {
                    var last = heads.get(b);
                    boolean acrossBar = last.measure() == first.measure() + 1;
                    if (last.staff() != first.staff()
                            || (!acrossBar && last.measure() != first.measure())
                            || last.x() < right + gap * .25f
                            || last.x() - right > gap * 3
                            || Math.hypot(last.x() - right, last.y() - rightY) > gap * 3
                            || (last.y() - first.y()) * slope <= 0) continue;
                    boolean between = false;
                    for (int k = 0; k < heads.size(); k++) {
                        var other = heads.get(k);
                        if (k != a
                                && k != b
                                && other.staff() == first.staff()
                                && (acrossBar
                                        ? (other.measure() == first.measure()
                                                    && other.x() >= first.x() - gap * .5f)
                                                || (other.measure() == last.measure()
                                                    && other.x() <= last.x() + gap * .5f)
                                        : other.measure() == first.measure()
                                                && other.x() >= first.x() - gap * .5f
                                                && other.x() <= last.x() + gap * .5f)) {
                            between = true;
                            break;
                        }
                    }
                    if (between) continue;
                    double score =
                            Math.hypot(left - first.x(), leftY - first.y())
                                    + Math.hypot(last.x() - right, last.y() - rightY);
                    if (score < best) {
                        best = score;
                        source = a;
                        target = b;
                    }
                }
            }
            if (source < 0) continue;
            float gap = heads.get(source).gap();
            if (!alternatingRibbon(queue, count, width, cx, cy, ux, uy, gap)) continue;
            Link link = new Link(source, target);
            if (!result.contains(link)) result.add(link);
        }
        return List.copyOf(result);
    }

    /** A semantic head inside a long, thin wave belongs to the wave itself. */
    static boolean ownsInterior(byte[] labels, byte[] gray, int width, int height,
            int headLeft, int headTop, int headRight, int headBottom, int headArea, float gap) {
        if (labels == null || gray == null || labels.length != (long) width * height
                || labels.length != gray.length || gap < 7 || gap > 32 || headArea < 1) return false;
        int left = Math.max(0, headLeft - Math.round(gap * 12));
        int right = Math.min(width - 1, headRight + Math.round(gap * 12));
        int top = Math.max(0, headTop - Math.round(gap * 1.9f));
        int bottom = Math.min(height - 1, headBottom + Math.round(gap * 1.9f));
        int w = right - left + 1, h = bottom - top + 1;
        if (w < gap * 5 || h <= 0) return false;
        // A printed music-symbol segment on both sides makes the detailed local scan rare.
        if (!mentionsWave(labels, gray, width, Math.max(left, headLeft - Math.round(gap * 1.4f)),
                    headLeft - 1, top, bottom)
                || !mentionsWave(labels, gray, width, headRight + 1,
                    Math.min(right, headRight + Math.round(gap * 1.4f)), top, bottom)) return false;
        byte[] crop = new byte[w * h];
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) {
                int at = y * width + x;
                crop[(y - top) * w + x - left] = labels[at] == OmrMeasurePostProcessor.STAFF
                        ? (byte) 255 : gray[at];
            }
        var ink = new PackedInkFlood(crop, w, h, 150, 1, 0);
        int count;
        while ((count = ink.next()) != 0) {
            if (ink.left == 0 || ink.right == w - 1 || ink.top == 0 || ink.bottom == h - 1
                    || ink.right - ink.left + 1 < gap * 5
                    || ink.right - ink.left + 1 > gap * 15
                    || headLeft - left - ink.left < gap
                    || ink.right - (headRight - left) < gap) continue;
            int coveredHead = 0;
            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            for (int i = 0; i < count; i++) {
                int pixel = ink.queue[i], x = pixel % w, y = pixel / w;
                int px = x + left, py = y + top;
                if (px >= headLeft && px <= headRight && py >= headTop && py <= headBottom
                        && labels[py * width + px] == OmrMeasurePostProcessor.NOTEHEAD) coveredHead++;
                sx += x; sy += y; sxx += (double) x * x; syy += (double) y * y; sxy += (double) x * y;
            }
            if (coveredHead < headArea * .5f) continue;
            double cx = sx / count, cy = sy / count;
            double vx = sxx / count - cx * cx, vy = syy / count - cy * cy;
            double angle = .5 * Math.atan2(2 * (sxy / count - cx * cy), vx - vy);
            if (Math.abs(angle) <= .35 && alternatingRibbon(ink.queue, count, w, cx, cy,
                    Math.cos(angle), Math.sin(angle), gap, true)) return true;
        }
        return false;
    }

    private static boolean mentionsWave(byte[] labels, byte[] gray, int width,
            int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) {
                int at = y * width + x;
                if (labels[at] == OmrMeasurePostProcessor.SYMBOL && (gray[at] & 255) < 150) return true;
            }
        return false;
    }

    private static boolean alternatingRibbon(
            int[] pixels,
            int count,
            int width,
            double cx,
            double cy,
            double ux,
            double uy,
            float gap) {
        return alternatingRibbon(pixels, count, width, cx, cy, ux, uy, gap, false);
    }

    private static boolean alternatingRibbon(int[] pixels, int count, int width,
            double cx, double cy, double ux, double uy, float gap, boolean ownsHead) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            int x = pixels[i] % width, y = pixels[i] / width;
            double along = (x - cx) * ux + (y - cy) * uy;
            min = Math.min(min, along);
            max = Math.max(max, along);
        }
        int bins = (int) Math.ceil(max - min) + 1;
        if (bins < 12 || bins > gap * 15) return false;
        double[] sums = new double[bins], low = new double[bins], high = new double[bins];
        int[] counts = new int[bins];
        Arrays.fill(low, Double.POSITIVE_INFINITY);
        Arrays.fill(high, Double.NEGATIVE_INFINITY);
        for (int i = 0; i < count; i++) {
            int x = pixels[i] % width, y = pixels[i] / width;
            int bin = Math.min(bins - 1, (int) Math.floor((x - cx) * ux + (y - cy) * uy - min));
            double normal = -(x - cx) * uy + (y - cy) * ux;
            sums[bin] += normal;
            counts[bin]++;
            low[bin] = Math.min(low[bin], normal);
            high[bin] = Math.max(high[bin], normal);
        }
        int sign = 0, changes = 0, covered = 0, wide = 0;
        double threshold = Math.max(.65, gap * .055);
        for (int i = 2; i < bins - 2; i++) {
            if (counts[i] == 0) continue;
            if (high[i] - low[i] > gap * .55) {
                if (!ownsHead || high[i] - low[i] > gap * .72 || ++wide > 1) return false;
            }
            covered++;
            double sum = 0;
            int samples = 0;
            for (int j = Math.max(0, i - 1); j <= Math.min(bins - 1, i + 1); j++)
                if (counts[j] > 0) {
                    sum += sums[j] / counts[j];
                    samples++;
                }
            double normal = sum / samples;
            int next = normal > threshold ? 1 : normal < -threshold ? -1 : 0;
            if (next != 0) {
                if (sign != 0 && next != sign) changes++;
                sign = next;
            }
        }
        return covered >= (bins - 4) * .9 && changes >= (ownsHead ? 8 : 4);
    }
}
