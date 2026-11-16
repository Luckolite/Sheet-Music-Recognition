// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Excludes independently identified slurs and fingerings from the opening tuplet mark search. */
final class OpeningTripletContextInk {
    private OpeningTripletContextInk() {}

    record Pair(byte[] original, byte[] corrected) {}

    private record Curve(RawNumeralComponents.Component source, int[] top, int[] bottom) {}

    static Pair withoutContext(byte[] original, byte[] corrected, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PlayingTechniqueDetector.Word> words) {
        return withoutContext(original, corrected, width, height, gap, headsX, headsY, words, null);
    }

    static Pair withoutFollowingContext(byte[] original, byte[] corrected, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PlayingTechniqueDetector.Word> words, float[] tipsY) {
        return withoutContext(original, corrected, width, height, gap, headsX, headsY, words, tipsY);
    }

    private static Pair withoutContext(byte[] original, byte[] corrected, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PlayingTechniqueDetector.Word> words, float[] tipsY) {
        boolean following = tipsY != null;
        if (gap < 10 || gap > 25 || headsX.length != 18 || headsY.length != 18)
            return new Pair(original, corrected);
        float topHead = headsY[0], bottomHead = headsY[0];
        for (float y : headsY) {
            topHead = Math.min(y, topHead);
            bottomHead = Math.max(y, bottomHead);
        }
        int l = Math.max(0, Math.round(headsX[0] - (following ? 19 : 8) * gap));
        int r = Math.min(width - 1, Math.round(headsX[17] + 26 * gap));
        int t = Math.max(0, Math.round(topHead - 7 * gap));
        int b = Math.min(height - 1, Math.round(bottomHead + 11 * gap));
        var sourceCurves = curves(original, width, height, l, t, r, b, gap, following);
        var normalizedCurves = curves(corrected, width, height, l, t, r, b, gap, following);
        byte[] a = null, z = null;
        for (var curve : sourceCurves) {
            var peers = normalizedCurves.stream().filter(other -> sameBox(curve.source(), other.source())).toList();
            if (peers.size() != 1) continue;
            if (a == null) {
                a = original.clone();
                z = corrected.clone();
            }
            eraseCurve(a, width, curve);
            eraseCurve(z, width, peers.get(0));
        }

        for (var word : words) {
            String value = word.text().strip();
            if (!value.equals("1") && !value.equals("4")) continue;
            int left = Math.round(word.left() * width) - 3;
            int right = Math.round(word.right() * width) + 3;
            int top = Math.round(word.top() * height) - 3;
            int bottom = Math.round(word.bottom() * height) + 3;
            if (left < l || right > r || top < t || bottom > b
                    || right - left > 3 * gap || bottom - top > 3 * gap) continue;
            var first = fingering(original, width, height, left, top, right, bottom, gap, value, headsX, headsY, tipsY);
            var second = fingering(corrected, width, height, left, top, right, bottom, gap, value, headsX, headsY, tipsY);
            if (first == null || second == null || !sameBox(first, second)) continue;
            if (a == null) {
                a = original.clone();
                z = corrected.clone();
            }
            erase(a, first);
            erase(z, second);
        }
        return a == null ? new Pair(original, corrected) : new Pair(a, z);
    }

    private static List<Curve> curves(byte[] data, int width, int height, int l, int t, int r, int b, float gap, boolean following) {
        if (l >= r || t >= b || (long) (r - l + 1) * (b - t + 1) > 650_000) return List.of();
        var graph = RawNumeralComponents.captureLongWindow(data, width, height, l, t, r, b);
        var result = new ArrayList<Curve>();
        for (var c : graph.components()) {
            int span = c.right - c.left + 1, rise = c.bottom - c.top + 1;
            if (span < 12 * gap || span > 44 * gap || rise < .8f * gap || rise > 8 * gap
                    || span < 3 * rise || c.left <= l || c.right >= r || c.top <= t || c.bottom >= b
                    || c.size() > span * gap * .38f) continue;
            int[] start = new int[span], end = new int[span], counts = new int[span];
            Arrays.fill(start, height);
            Arrays.fill(end, -1);
            for (int i = 0; i < c.size(); i++) {
                int pixel = c.pixel(i), x = pixel % width - c.left, y = pixel / width;
                counts[x]++;
                start[x] = Math.min(start[x], y);
                end[x] = Math.max(end[x], y);
            }
            if (following) trimAttachedDot(start, end, counts, gap);
            if (returningCurve(start, end, counts, gap)) result.add(new Curve(c,start,end));
        }
        return result;
    }

    /** Restore the bow profile through one adjacent tiny dot footprint. */
    private static void trimAttachedDot(int[] start, int[] end, int[] counts, float gap) {
        int from = -1, to = -1;
        for (int x = 0; x < counts.length; x++) {
            if (counts[x] <= gap * .43f) continue;
            if (to >= 0 && x - to > 1 || counts[x] > gap * .84f) return;
            if (from < 0) from = x;
            to = x;
        }
        if (from <= 0 || to >= counts.length - 1 || to - from + 1 > gap * .55f) return;
        if (from < counts.length * .2f || to > counts.length * .8f) return;
        int before = from - 1, after = to + 1;
        int minimum = Math.min(start[before], start[after]);
        int maximum = Math.max(start[before], start[after]);
        // The protrusion can project off either side of a slur; erase only its continuing arc band.
        boolean preserveBelow = true, preserveAbove = true;
        for (int x = from; x <= to; x++) {
            if (start[x] < minimum - 1 || start[x] > maximum + 1) preserveBelow = false;
            if (end[x] < Math.min(end[before],end[after]) - 1
                    || end[x] > Math.max(end[before],end[after]) + 1) preserveAbove = false;
        }
        if (preserveAbove == preserveBelow) return;
        for (int x = from; x <= to; x++) {
            float at = (x - before) / (float) (after - before);
            if (preserveBelow) end[x] = Math.round(end[before] + at * (end[after] - end[before]));
            else start[x] = Math.round(start[before] + at * (start[after] - start[before]));
            counts[x] = end[x] - start[x] + 1;
        }
    }

    private static boolean returningCurve(int[] start, int[] end, int[] counts, float gap) {
        int span = start.length;
        float[] lift = new float[5];
        int[] bins = new int[5];
        float begin = (start[0] + end[0]) * .5f, finish = (start[span - 1] + end[span - 1]) * .5f;
        if (Math.abs(finish - begin) > 3 * gap) return false;
        for (int x = 0; x < span; x++) {
            if (counts[x] < 1 || counts[x] > gap * .43f || counts[x] != end[x] - start[x] + 1
                    || x > 0 && Math.abs(start[x] + end[x] - start[x - 1] - end[x - 1]) > gap * .55f)
                return false;
            int bin = x * lift.length / span;
            float line = begin + (finish - begin) * x / (span - 1);
            lift[bin] += (start[x] + end[x]) * .5f - line;
            bins[bin]++;
        }
        for (int i = 0; i < lift.length; i++) lift[i] /= bins[i];
        float sign = Math.signum(lift[2]);
        return Math.abs(lift[2]) > gap * .7f
                && (lift[1] - lift[0]) * sign > gap * .17f
                && (lift[2] - lift[1]) * sign >= -gap * .08f
                && (lift[2] - lift[3]) * sign >= -gap * .08f
                && (lift[3] - lift[4]) * sign > gap * .17f;
    }

    private static RawNumeralComponents.Component fingering(byte[] plane, int width, int height,
            int left, int top, int right, int bottom, float gap, String number, float[] x, float[] y, float[] tips) {
        var graph = RawNumeralComponents.capture(plane, width, height, left, top, right, bottom,
                204, () -> {}, () -> {});
        RawNumeralComponents.Component found = null;
        for (var c : graph.components()) {
            int w = c.right - c.left + 1, h = c.bottom - c.top + 1;
            if (c.left <= left || c.right >= right || c.top <= top || c.bottom >= bottom
                    || w < gap * .38f || w > gap * 1.3f || h < .75f * gap || h > 1.65f * gap
                    || !aboveOneHead(x, y, tips, c, gap) || !fingerShape(c, width, number, tips != null)) continue;
            if (found != null) return null;
            found = c;
        }
        return found;
    }

    private static boolean aboveOneHead(float[] x, float[] y, float[] tips, RawNumeralComponents.Component glyph, float gap) {
        int matches = 0;
        for (int i = 0; i < x.length; i++)
            if (Math.abs((glyph.left + glyph.right) * .5f - x[i]) <= .46f * gap
                    && y[i] - glyph.bottom >= .9f * gap
                    && (y[i] - glyph.bottom <= 3.6f * gap
                        || tips != null && y[i] - glyph.bottom <= 5.3f * gap
                            && tips[i] < y[i] && tips[i] - glyph.bottom >= gap
                            && tips[i] - glyph.bottom <= 1.8f * gap)) matches++;
        return matches == 1;
    }

    private static boolean fingerShape(RawNumeralComponents.Component glyph, int width, String number, boolean following) {
        int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
        int[] ink = new int[h], start = new int[h], end = new int[h];
        Arrays.fill(start,w);
        Arrays.fill(end,-1);
        double topSum = 0, lowerSum = 0;
        int topCount = 0, lowerCount = 0;
        for (int i = 0; i < glyph.size(); i++) {
            int pos = glyph.pixel(i), row = pos / width - glyph.top, col = pos % width - glyph.left;
            ink[row]++;
            start[row] = Math.min(start[row], col);
            end[row] = Math.max(end[row], col);
            if (row < h / 3) {
                topCount++;
                topSum += col;
            } else if (row >= 4 * h / 5) {
                lowerCount++;
                lowerSum += col;
            }
        }
        if (number.equals("1")) {
            if (w > .82f * h) return false;
            int splitRows = 0;
            for (int row = 0; row < h; row++) {
                if (ink[row] != end[row] - start[row] + 1) splitRows++;
            }
            if (splitRows == 0) return true;
            if (!following || splitRows > Math.max(1, h / 9)) return false;
            for (int col = w / 4; col <= 3 * w / 4; col++) {
                int run = 0;
                for (int i = 0; i < glyph.size(); i++) {
                    if (glyph.pixel(i) % width - glyph.left == col) run++;
                }
                if (run >= h * .87f) return true;
            }
            return false;
        }
        boolean counter = false, crossbar = false;
        for (int row = h / 3; row < 4 * h / 5; row++) {
            counter |= ink[row] + 1 <= end[row] - start[row] + 1;
            crossbar |= row >= h / 2 && ink[row] >= .88f * w;
        }
        if (topCount == 0 || lowerCount == 0 || !crossbar
                || lowerSum / lowerCount - topSum / topCount < .10 * w) return false;
        if (counter) return true;
        return following && ink[0] <= .55f * w && ink[h - 1] <= .60f * w
                && start[h - 1] >= .35f * w && end[h / 5] <= .74f * w;
    }

    private static boolean sameBox(RawNumeralComponents.Component a, RawNumeralComponents.Component b) {
        return Math.abs(a.left-b.left)<=1 && Math.abs(a.right-b.right)<=1
                && Math.abs(a.top-b.top)<=1 && Math.abs(a.bottom-b.bottom)<=1;
    }

    private static void eraseCurve(byte[] plane, int width, Curve curve) {
        var c = curve.source();
        for (int i = 0; i < curve.top().length; i++) {
            int x = c.left + i;
            for (int y = curve.top()[i] - 1; y <= curve.bottom()[i] + 1; y++)
                plane[y * width + x] = (byte) 255;
        }
    }

    private static void erase(byte[] plane, RawNumeralComponents.Component glyph) {
        for (int i = 0; i < glyph.size(); i++) plane[glyph.pixel(i)] = (byte) 255;
    }
}
