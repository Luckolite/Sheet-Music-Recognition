// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.util.regex.Pattern;

/** Volta evidence combines a connected straight roof/downward hook with its printed selectors. */
final class PrintedEndingBrackets {
    record Mark(
            int start,
            int end,
            List<Integer> passes,
            String text,
            float x,
            int staffIndex,
            int staffCount,
            boolean openRight,
            boolean precedingRoof) {
        Mark(
                int start,
                int end,
                List<Integer> passes,
                String text,
                float x,
                int staffIndex,
                int staffCount) {
            this(start, end, passes, text, x, staffIndex, staffCount, false, false);
        }
    }

    private static final Pattern SELECTOR =
            Pattern.compile("\\d{1,3}\\.?\\s*(?:,\\s*\\d{1,3}\\.?\\s*)*");
    private static final Pattern INTEGER = Pattern.compile("\\d+");

    private PrintedEndingBrackets() {}

    static List<Mark> detect(
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<PlayingTechniqueDetector.Word> words) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || staffs == null
                || measures == null
                || words == null
                || words.isEmpty()) return List.of();
        var result = new ArrayList<Mark>();
        boolean[] seen = new boolean[0];
        int[] runs = new int[256];
        for (var staff : staffs) {
            float gap = staff.gap();
            if (!Float.isFinite(gap) || gap < 5 || gap > Math.min(width, height) / 8f) continue;
            int top = Math.max(0, Math.round(staff.top() - gap * 8));
            int bottom = Math.min(height - 1, Math.round(staff.top() - gap * .3f));
            if (bottom <= top) continue;
            int rows = bottom - top + 1, bandSize = width * rows, offset = top * width;
            if (seen.length < bandSize) seen = new boolean[bandSize];
            else Arrays.fill(seen, 0, bandSize, false);
            for (int seed = 0; seed < bandSize; seed++) {
                if (seen[seed] || (gray[offset + seed] & 255) >= 170) continue;
                int first = seed / width, last = first, left = width, right = -1;
                int queued = 2;
                runs[0] = runStart(gray, offset, seed, first * width);
                runs[1] = runEnd(gray, offset, seed, (first + 1) * width);
                Arrays.fill(seen, runs[0], runs[1] + 1, true);
                for (int take = 0; take < queued; take += 2) {
                    int from = runs[take], to = runs[take + 1], y = from / width;
                    int row = y * width, x0 = from - row, x1 = to - row;
                    left = Math.min(left, x0);
                    right = Math.max(right, x1);
                    last = Math.max(last, y);
                    int neighborLeft = Math.max(0, x0 - 1);
                    int neighborRight = Math.min(width - 1, x1 + 1);
                    for (int step = -1; step <= 1; step += 2) {
                        if (y + step < 0 || y + step >= rows) continue;
                        int neighbor = row + step * width;
                        for (int next = neighbor + neighborLeft; next <= neighbor + neighborRight; next++) {
                            if (seen[next] || (gray[offset + next] & 255) >= 170) continue;
                            int start = runStart(gray, offset, next, neighbor);
                            int end = runEnd(gray, offset, next, neighbor + width);
                            Arrays.fill(seen, start, end + 1, true);
                            if (queued == runs.length) runs = Arrays.copyOf(runs, Math.multiplyExact(queued, 2));
                            runs[queued++] = start;
                            runs[queued++] = end;
                            next = end;
                        }
                    }
                }
                int w = right - left + 1, h = last - first + 1;
                if (w <= gap * 6 || h <= gap * .5f || h >= gap * 7 || first == 0) continue;
                int[] roof = new int[w], floor = new int[w], count = new int[w];
                Arrays.fill(roof, h);
                for (int i = 0; i < queued; i += 2) {
                    int row = runs[i] / width;
                    int start = runs[i] - row * width - left;
                    int end = runs[i + 1] - row * width - left;
                    int y = row - first;
                    for (int x = start; x <= end; x++) {
                        roof[x] = Math.min(roof[x], y);
                        floor[x] = Math.max(floor[x], y);
                        count[x]++;
                    }
                }
                int margin = Math.round(gap), n = 0;
                double sx = 0, sy = 0, sxx = 0, sxy = 0;
                for (int x = margin; x < w - margin; x++)
                    if (roof[x] < h) {
                        n++;
                        sx += x;
                        sy += roof[x];
                        sxx += (double) x * x;
                        sxy += (double) x * roof[x];
                    }
                if (n < (w - 2 * margin) * .97 || n < 3) continue;
                double denominator = n * sxx - sx * sx;
                if (denominator <= 0) continue;
                double slope = (n * sxy - sx * sy) / denominator, intercept = (sy - slope * sx) / n;
                if (Math.abs(slope) > .12) continue;
                int straight = 0, thin = 0;
                for (int x = margin; x < w - margin; x++)
                    if (roof[x] < h) {
                        if (Math.abs(roof[x] - (slope * x + intercept)) <= gap * .15) straight++;
                        if (floor[x] - roof[x] + 1 <= Math.max(2, gap * .35f)) thin++;
                    }
                if (straight < n * .98 || thin < n * .95) continue;
                var hooks = new ArrayList<Integer>();
                var depths = new ArrayList<Integer>();
                for (int x = 0; x < w; x++) {
                    int depth = floor[x] - (int) Math.round(slope * x + intercept);
                    if (depth < gap || count[x] < depth * .85) continue;
                    int begin = x, deepest = depth;
                    while (x + 1 < w) {
                        int nextDepth =
                                floor[x + 1] - (int) Math.round(slope * (x + 1) + intercept);
                        if (nextDepth < gap || count[x + 1] < nextDepth * .85) break;
                        deepest = Math.max(deepest, nextDepth);
                        x++;
                    }
                    if (x - begin + 1 <= Math.max(2, gap * .4f)) {
                        hooks.add(begin);
                        depths.add(deepest);
                    }
                }
                for (int hookIndex = 0; hookIndex < hooks.size(); hookIndex++) {
                    int origin = hooks.get(hookIndex);
                    int limit = hookIndex + 1 < hooks.size() ? hooks.get(hookIndex + 1) : w - 1;
                    if (limit - origin <= gap * 6) continue;
                    int segmentLeft = left + origin, segmentRight = left + limit;
                    int hook = depths.get(hookIndex);
                    PlayingTechniqueDetector.Word matched = null;
                    List<Integer> passes = null;
                    boolean ambiguous = false;
                    for (var word : words) {
                        var parsed = passes(word.text());
                        if (parsed.isEmpty()) continue;
                        float l = word.left() * width,
                                r = word.right() * width,
                                t = word.top() * height,
                                b = word.bottom() * height;
                        double roofY = top + first + slope * (l - left) + intercept;
                        if (l < segmentLeft - gap * .3f
                                || l > segmentLeft + 5 * gap
                                || r >= segmentRight
                                || t < roofY - gap * .3f
                                || b > roofY + Math.min(4 * gap, hook)) {
                            // OCR boxes can include whitespace and the adjacent bracket stroke.
                            // Retry only nearby selectors, using their ink outside this proved
                            // roof.
                            if (l < segmentLeft - gap
                                    || l > segmentLeft + 5 * gap
                                    || r >= segmentRight
                                    || t < roofY - gap
                                    || b > roofY + Math.min(4 * gap, hook) + gap) continue;
                            int inkLeft = width,
                                    inkRight = -1,
                                    inkTop = height,
                                    inkBottom = -1,
                                    inkCount = 0;
                            for (int yy = Math.max(0, (int) Math.floor(t));
                                    yy < Math.min(height, (int) Math.ceil(b));
                                    yy++) {
                                for (int xx = Math.max(0, (int) Math.floor(l));
                                        xx < Math.min(width, (int) Math.ceil(r));
                                        xx++) {
                                    if ((gray[yy * width + xx] & 255) >= 170) continue;
                                    int rx = xx - left, ry = yy - top - first;
                                    if (rx >= 0 && rx < w && ry >= roof[rx] && ry <= floor[rx])
                                        continue;
                                    inkLeft = Math.min(inkLeft, xx);
                                    inkRight = Math.max(inkRight, xx);
                                    inkTop = Math.min(inkTop, yy);
                                    inkBottom = Math.max(inkBottom, yy);
                                    inkCount++;
                                }
                            }
                            if (inkCount < gap
                                    || inkRight - inkLeft < gap * .25f
                                    || inkBottom - inkTop < gap * .5f) continue;
                            l = inkLeft;
                            r = inkRight + 1;
                            t = inkTop;
                            b = inkBottom + 1;
                            roofY = top + first + slope * (l - left) + intercept;
                            if (l < segmentLeft
                                    || l > segmentLeft + 5 * gap
                                    || r >= segmentRight
                                    || t < roofY
                                    || b > roofY + Math.min(4 * gap, hook) + gap * .15f) continue;
                        }
                        if (matched != null) {
                            ambiguous = true;
                            break;
                        }
                        matched = word;
                        passes = parsed;
                    }
                    if (matched == null || ambiguous) continue;
                    int start =
                            PrintedRepeatGlyphs.boundary(
                                    measures, staff, segmentLeft, false, width, height);
                    int end =
                            PrintedRepeatGlyphs.boundary(
                                    measures, staff, segmentRight, true, width, height);
                    if (start < 0 || end <= start) continue;
                    var mark =
                            new Mark(
                                    start,
                                    end,
                                    passes,
                                    matched.text(),
                                    segmentLeft / (float) width,
                                    staff.index(),
                                    staff.count(),
                                    hookIndex + 1 == hooks.size(),
                                    origin > gap * 6);
                    if (!result.contains(mark)) result.add(mark);
                }
            }
        }
        result.sort(Comparator.comparingInt(Mark::start).thenComparingInt(Mark::end));
        return List.copyOf(result);
    }

    static List<Integer> passes(String text) {
        if (text == null || !SELECTOR.matcher(text.trim()).matches()) return List.of();
        var values = new ArrayList<Integer>();
        var matcher = INTEGER.matcher(text);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group());
            if (value < 1 || value > 128 || values.contains(value)) return List.of();
            values.add(value);
        }
        return List.copyOf(values);
    }
    private static final java.lang.invoke.VarHandle INK_WORDS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                    long[].class, java.nio.ByteOrder.nativeOrder());
    private static final long INK_HIGH_BITS = 0x8080808080808080L;
    private static final long INK_BIAS = 0x0101010101010101L * (170 - 128);

    private static boolean inkWord(byte[] gray, int offset) {
        long tones = (long) INK_WORDS.get(gray, offset);
        return (((tones | INK_HIGH_BITS) - INK_BIAS) & tones & INK_HIGH_BITS) == 0;
    }

    private static int runStart(byte[] gray, int offset, int seed, int rowStart) {
        while (seed - rowStart >= Long.BYTES && inkWord(gray, offset + seed - Long.BYTES))
            seed -= Long.BYTES;
        while (seed > rowStart && (gray[offset + seed - 1] & 255) < 170) seed--;
        return seed;
    }

    private static int runEnd(byte[] gray, int offset, int seed, int rowEnd) {
        while (rowEnd - seed > Long.BYTES && inkWord(gray, offset + seed + 1))
            seed += Long.BYTES;
        while (seed + 1 < rowEnd && (gray[offset + seed + 1] & 255) < 170) seed++;
        return seed;
    }

}
