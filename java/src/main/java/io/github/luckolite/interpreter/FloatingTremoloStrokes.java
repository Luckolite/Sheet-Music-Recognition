// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Reads detached tremolo strokes in the space between independently established hollow heads. */
final class FloatingTremoloStrokes {
    record Mark(int left, int top, int right, int bottom,
            double center, double slope, List<Band> bands) {
        boolean onStroke(int x, int y) {
            double projected = y - slope * (x - center);
            for (Band band : bands)
                if (projected >= band.first - 2 && projected <= band.last + 2) return true;
            return false;
        }
    }

    private record Band(int first, int last) {
        double center() {
            return (first + last) * .5;
        }

        int thickness() {
            return last - first + 1;
        }
    }

    static Mark find(
            byte[] labels, byte[] gray, int width, int height,
            int leftHead, int rightHead, int firstRow, int lastRow, double gap) {
        int distance = rightHead - leftHead;
        if (gray == null || labels == null || gray.length != (long) width * height
                || labels.length != gray.length || gap < 7 || gap > 40
                || distance < 3 * gap || distance > 11 * gap
                || leftHead < 0 || rightHead >= width) return null;
        int x0 = leftHead + (int) Math.round(.32 * distance);
        int x1 = leftHead + (int) Math.round(.69 * distance);
        double midpoint = (x0 + x1) * .5;
        int span = x1 - x0 + 1;
        int usableInk = (int) Math.ceil(span * .85), usableLabels = (int) Math.ceil(span * .60);
        if (lastRow - firstRow > 12 * gap) return null;
        int first = Math.max(2, firstRow), last = Math.min(height - 3, lastRow);
        Mark best = null;
        int bestQuality = 0;
        double[] offsets = new double[span];
        for (int step = -32; step <= 32; step++) {
            if (Math.abs(step) < 6) continue;
            double slope = step * .015;
            for (int column = 0; column < span; column++)
                offsets[column] = slope * (x0 + column - midpoint);
            var bands = new ArrayList<Band>();
            int run = -1;
            for (int y = first; y <= last + 1; y++) {
                int raw = 0, beam = 0;
                if (y <= last)
                    for (int col = 0; col < span; col++) {
                        int row = (int) Math.round(y + offsets[col]);
                        if (row < 0 || row >= height) continue;
                        int pixel = row * width + x0 + col;
                        if ((gray[pixel] & 255) < 145) raw++;
                        if (labels[pixel] == 5) beam++;
                    }
                if (raw >= usableInk && beam >= usableLabels) {
                    if (run < 0) run = y;
                } else if (run >= 0) {
                    int thickness = y - run;
                    if (thickness >= .15 * gap && thickness <= .70 * gap)
                        bands.add(new Band(run, y - 1));
                    run = -1;
                }
            }
            for (int i = 0; i + 2 < bands.size(); i++) {
                var a = bands.get(i);
                var b = bands.get(i + 1);
                var c = bands.get(i + 2);
                double ab = b.center() - a.center(), bc = c.center() - b.center();
                if (ab < .52 * gap || ab > 1.03 * gap || bc < .52 * gap || bc > 1.03 * gap
                        || Math.abs(ab - bc) > .16 * gap
                        || i > 0 && Math.abs(a.center() - bands.get(i - 1).center() - ab) < .16 * gap
                        || i + 3 < bands.size()
                                && Math.abs(bands.get(i + 3).center() - c.center() - bc) < .16 * gap)
                    continue;
                int quality = a.thickness() + b.thickness() + c.thickness();
                if (quality <= bestQuality) continue;
                Mark mark = finiteMark(labels, gray, width, height, leftHead, rightHead, midpoint,
                        slope, gap, List.of(a, b, c));
                if (mark == null) continue;
                best = mark;
                bestQuality = quality;
            }
        }
        return best;
    }

    private static Mark finiteMark(
            byte[] labels, byte[] gray, int width, int height, int leftHead, int rightHead,
            double center, double slope, double gap, List<Band> bands) {
        int left = rightHead, right = leftHead, top = height, bottom = 0;
        int latestLeft = leftHead, earliestRight = rightHead, tilted = 0;
        int inset = (int) Math.ceil(.30 * gap);
        int midpoint = (int) Math.round(center);
        for (Band band : bands) {
            int leftEdge = boundary(labels, gray, width, height, midpoint, leftHead + inset,
                    -1, center, slope, gap, band);
            int rightEdge = boundary(labels, gray, width, height, midpoint, rightHead - inset,
                    1, center, slope, gap, band);
            if (leftEdge <= leftHead + inset || rightEdge >= rightHead - inset
                    || rightEdge - leftEdge < 1.9 * gap) return null;
            if (tiltedBand(labels, gray, width, height, leftEdge, rightEdge, center, slope, gap, band)) tilted++;
            latestLeft = Math.max(latestLeft, leftEdge);
            earliestRight = Math.min(earliestRight, rightEdge);
            left = Math.min(left, leftEdge);
            right = Math.max(right, rightEdge);
            double a = slope * (leftEdge - center), b = slope * (rightEdge - center);
            top = Math.min(top, (int) Math.floor(band.first + Math.min(a, b)));
            bottom = Math.max(bottom, (int) Math.ceil(band.last + Math.max(a, b)));
        }
        if (tilted < 2 || latestLeft - left > .72 * gap || right - earliestRight > .72 * gap
                || top < 0 || bottom >= height) return null;
        return new Mark(left, top, right + 1, bottom + 1, center, slope, List.copyOf(bands));
    }

    private static boolean tiltedBand(
            byte[] labels, byte[] gray, int width, int height, int left, int right,
            double center, double slope, double gap, Band band) {
        int inset = (int) Math.round((right - left) * .2);
        int l = left + inset, r = right - inset;
        double first = strokeCenter(labels, gray, width, height, l, center, slope, gap, band);
        double last = strokeCenter(labels, gray, width, height, r, center, slope, gap, band);
        double measured = (last - first) / (r - l);
        return Math.abs(measured) >= .065 && Math.abs(measured - slope) <= .075;
    }

    private static double strokeCenter(
            byte[] labels, byte[] gray, int width, int height, int x,
            double center, double slope, double gap, Band band) {
        int observations = 0;
        double sum = 0;
        for (int column = x - 2; column <= x + 2; column++) {
            int expected = (int) Math.round(band.center() + slope * (column - center));
            int radius = (int) Math.ceil(gap * .46);
            int min = Math.max(0, expected - radius), max = Math.min(height - 1, expected + radius);
            int nearest = -1;
            for (int y = min; y <= max; y++)
                if (strokeInk(labels, gray, width, column, y)
                        && (nearest < 0 || Math.abs(y - expected) < Math.abs(nearest - expected)))
                    nearest = y;
            if (nearest < 0) continue;
            int top = nearest, bottom = nearest;
            while (top > min && strokeInk(labels, gray, width, column, top - 1)) top--;
            while (bottom < max && strokeInk(labels, gray, width, column, bottom + 1)) bottom++;
            int thickness = bottom - top + 1;
            if (thickness < gap * .15 || thickness > gap * .7) continue;
            sum += (top + bottom) * .5 - slope * (column - x);
            observations++;
        }
        return observations >= 3 ? sum / observations : Double.NaN;
    }

    private static boolean strokeInk(byte[] labels, byte[] gray, int width, int x, int y) {
        int p = y * width + x;
        return labels[p] == 5 && (gray[p] & 255) < 145;
    }

    private static int boundary(
            byte[] labels, byte[] gray, int width, int height, int midpoint, int limit,
            int direction, double center, double slope, double gap, Band band) {
        int edge = midpoint, blank = 0;
        for (int x = midpoint; (limit - x) * direction >= 0; x += direction) {
            int y = (int) Math.round(band.center() + slope * (x - center));
            if (y < 2 || y >= height - 2) break;
            boolean match = false;
            for (int dy = -2; dy <= 2; dy++) {
                int pixel = (y + dy) * width + x;
                if (labels[pixel] == 5 && (gray[pixel] & 255) < 145) {
                    match = true;
                    break;
                }
            }
            if (match) {
                edge = x;
                blank = 0;
            } else if (++blank > Math.max(2, (int) (.32 * gap))) break;
        }
        return edge;
    }
}
