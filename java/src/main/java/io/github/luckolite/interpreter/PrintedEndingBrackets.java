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

    private record Continuation(
            int start, int end, int staffIndex, int staffCount, boolean endsSystem,
            float right, float joinAllowance, boolean closingHook) {}

    private record EndingSpan(Mark mark, int left, int right, double roof, boolean needsPair) {}

    private static final Pattern SELECTOR =
            Pattern.compile("\\d{1,3}\\.?\\s*(?:[,&]\\s*\\d{1,3}\\.?\\s*)*");
    private static final Pattern INTEGER = Pattern.compile("\\d+");
    private static final Pattern SELECTOR_CHORD = Pattern.compile(
            "([12])\\.\\s*[A-G](?:[#bâ™¯â™­])?(?:(?:m|maj|min|sus|dim|aug)?[0-9]*)?(?:/[A-G](?:[#bâ™¯â™­])?)?");

    private record Selector(
            PlayingTechniqueDetector.Word word, List<Integer> passes, String text, boolean includesHook) {}

    private PrintedEndingBrackets() {}

    /** The scan frame spans the page well beyond any printed staff or ending hook. */
    private static int scanFrameInset(
            byte[] gray, int width, int height, int edge, int direction) {
        int margin = Math.min(24, width / 90);
        int top = height / 9, bottom = height - top;
        if (margin < 3 || bottom - top < 80) return 0;
        int matched = 0;
        for (; matched < margin; matched++) {
            int column = edge + direction * matched;
            int light = 0;
            int allowance = (bottom - top) / 50;
            for (int y = top, pixel = y * width + column; y < bottom; y++, pixel += width)
                if ((gray[pixel] & 255) >= 170 && ++light > allowance) break;
            if (light > allowance) break;
        }
        return matched >= 2 && matched < margin ? matched : 0;
    }

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
                || words == null) return List.of();
        var selectors = new ArrayList<Selector>();
        for (var word : words) {
            String text = word.text().trim();
            boolean includesHook = text.startsWith("|");
            if (includesHook) text = text.substring(1).trim();
            var parsed = passes(text);
            if (!parsed.isEmpty()) selectors.add(new Selector(word, parsed, text, includesHook));
        }
        int firstColumn = scanFrameInset(gray, width, height, 0, 1);
        int lastColumn = width - scanFrameInset(gray, width, height, width - 1, -1);
        var result = new ArrayList<Mark>();
        var continuations = new ArrayList<Continuation>();
        boolean[] seen = new boolean[0];
        int[] runs = new int[256];
        for (var staff : staffs) {
            float gap = staff.gap();
            if (!Float.isFinite(gap) || gap < 5 || gap > Math.min(width, height) / 8f) continue;
            int top = Math.max(0, Math.round(staff.top() - gap * 11));
            int bottom = Math.min(height - 1, Math.round(staff.top() - gap * .3f));
            if (bottom <= top) continue;
            int rows = bottom - top + 1, bandSize = width * rows, offset = top * width;
            var spans = new ArrayList<EndingSpan>();
            if (seen.length < bandSize) seen = new boolean[bandSize];
            else Arrays.fill(seen, 0, bandSize, false);
            if (firstColumn != 0 || lastColumn != width)
                for (int row = 0; row < bandSize; row += width) {
                    Arrays.fill(seen, row, row + firstColumn, true);
                    Arrays.fill(seen, row + lastColumn, row + width, true);
                }
            for (int seed = 0; seed < bandSize; seed++) {
                if (seen[seed] || (gray[offset + seed] & 255) >= 170) continue;
                int first = seed / width, last = first, left = width, right = -1;
                int queued = 2;
                runs[0] = runStart(gray, offset, seed, first * width + firstColumn);
                runs[1] = runEnd(gray, offset, seed, first * width + lastColumn);
                Arrays.fill(seen, runs[0], runs[1] + 1, true);
                for (int take = 0; take < queued; take += 2) {
                    int from = runs[take], to = runs[take + 1], y = from / width;
                    int row = y * width, x0 = from - row, x1 = to - row;
                    left = Math.min(left, x0);
                    right = Math.max(right, x1);
                    last = Math.max(last, y);
                    int neighborLeft = Math.max(firstColumn, x0 - 1);
                    int neighborRight = Math.min(lastColumn - 1, x1 + 1);
                    for (int step = -1; step <= 1; step += 2) {
                        if (y + step < 0 || y + step >= rows) continue;
                        int neighbor = row + step * width;
                        for (int next = neighbor + neighborLeft; next <= neighbor + neighborRight; next++) {
                            if (seen[next] || (gray[offset + next] & 255) >= 170) continue;
                            int start = runStart(gray, offset, next, neighbor + firstColumn);
                            int end = runEnd(gray, offset, next, neighbor + lastColumn);
                            Arrays.fill(seen, start, end + 1, true);
                            if (queued == runs.length) runs = Arrays.copyOf(runs, Math.multiplyExact(queued, 2));
                            runs[queued++] = start;
                            runs[queued++] = end;
                            next = end;
                        }
                    }
                }
                int w = right - left + 1, h = last - first + 1;
                // A full intermediate system may carry only the roof stroke.
                boolean possibleMiddle = w >= gap * 12 && h <= Math.max(2, Math.ceil(gap * .35f))
                        && staff.top() - (top + first) >= gap * 2.5f
                        && staff.top() - (top + first) <= gap * 8f;
                if (w <= gap * 6 || !possibleMiddle && h <= gap * .5f
                        || h >= gap * 10 || first == 0) continue;
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
                        if (floor[x] - roof[x] + 1 <= Math.max(2, (int) Math.ceil(gap * .35f))) thin++;
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
                    if (x - begin + 1 <= Math.max(2, Math.round(gap * .4f))) {
                        hooks.add(begin);
                        depths.add(deepest);
                    }
                }
                boolean closingHook = hooks.size() == 1 && w >= gap * 12
                        && hooks.get(0) > w - gap * 1.25f;
                if (closingHook || possibleMiddle && hooks.isEmpty()) {
                    int start = openingBoundary(measures, staff, left, -1, width, height);
                    int end = PrintedRepeatGlyphs.boundary(measures, staff, right, true, width, height);
                    if (start >= 0 && end > start) {
                        boolean lastBar = rowLast(measures, staff, end - 1, height);
                        if (closingHook || lastBar)
                            continuations.add(new Continuation(start, end, staff.index(), staff.count(),
                                    lastBar, right / (float) width, gap * 1.1f / width, closingHook));
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
                    String matchedText = null;
                    boolean ambiguous = false;
                    for (var selector : selectors) {
                        var word = selector.word();
                        float l = word.left() * width,
                                r = word.right() * width,
                                t = word.top() * height,
                                b = word.bottom() * height;
                        if (selector.includesHook()
                                && (l > segmentLeft + gap * .15f || r <= segmentLeft)) continue;
                        double roofY = top + first + slope * (l - left) + intercept;
                        if (l < segmentLeft - gap * .3f
                                || l > segmentLeft + 5 * gap
                                || r >= segmentRight
                                || t < roofY - gap * .3f
                                || b > roofY + Math.min(4 * gap, hook)) {
                            // OCR boxes can include whitespace and the adjacent bracket stroke.
                            // Retry only nearby selectors, using their ink outside this proved
                            // roof.
                            if (l < segmentLeft - gap * 1.3f
                                    || l > segmentLeft + 5 * gap
                                    || r >= segmentRight
                                    || t < roofY - gap
                                    || b > roofY + Math.min(4 * gap, hook) + gap) continue;
                            // A wide OCR box can include the adjacent ending's closing hook.
                            int firstSelectorColumn = Math.max(0, (int) Math.floor(l));
                            if (l < segmentLeft - gap) firstSelectorColumn = segmentLeft;
                            int inkLeft = width,
                                    inkRight = -1,
                                    inkTop = height,
                                    inkBottom = -1,
                                    inkCount = 0;
                            for (int yy = Math.max(0, (int) Math.floor(t));
                                    yy < Math.min(height, (int) Math.ceil(b));
                                    yy++) {
                                for (int xx = firstSelectorColumn;
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
                        passes = selector.passes();
                        matchedText = selector.text();
                    }
                    if (ambiguous) continue;
                    double roofY = top + first + slope * origin + intercept;
                    boolean needsPair = false;
                    if (matched == null) {
                        matched = isolatedSelector(gray, width, height, segmentLeft, segmentRight,
                                roofY, slope, hook, gap, words,
                                joinedChordSelector(words, width, height, segmentLeft, roofY, gap));
                        if (matched == null) {
                            matched = plainSelector(gray, width, height, segmentLeft, segmentRight,
                                    roofY, slope, hook, gap);
                            needsPair = true;
                        }
                        if (matched == null) continue;
                        matchedText = matched.text();
                        passes = passes(matchedText);
                    }
                    int start =
                            PrintedRepeatGlyphs.boundary(
                                    measures, staff, segmentLeft, false, width, height);
                    if (start < 0)
                        start = openingBoundary(measures, staff, segmentLeft, segmentRight, width, height);
                    int end =
                            PrintedRepeatGlyphs.boundary(
                                    measures, staff, segmentRight, true, width, height);
                    if (start < 0 || end <= start) continue;
                    var mark =
                            new Mark(
                                    start,
                                    end,
                                    passes,
                                    matchedText,
                                    segmentLeft / (float) width,
                                    staff.index(),
                                    staff.count(),
                                    hookIndex + 1 == hooks.size(),
                                    origin > gap * 6);
                    spans.add(new EndingSpan(mark, segmentLeft, segmentRight, roofY, needsPair));
                }
            }
            for (var span : spans)
                if ((!span.needsPair || hasPairedSelector(span, spans, gap))
                        && !result.contains(span.mark)) result.add(span.mark);
        }
        for (int i = 0; i < result.size(); i++) {
            var mark = result.get(i);
            if (!mark.openRight || !mark.passes.contains(1)) continue;
            // A roof without a hook can connect systems but cannot close an ending.
            int boundary = mark.end;
            while (boundary < measures.size()) {
                final int start = boundary;
                var matches = continuations.stream().distinct().filter(c -> c.start == start
                        && c.staffIndex == mark.staffIndex && c.staffCount == mark.staffCount
                        && measures.get(c.start).top() > measures.get(start - 1).bottom()
                        && (c.endsSystem || adjacentSecondEnding(c, result))).toList();
                if (matches.size() != 1) break;
                var continuation = matches.get(0);
                boundary = continuation.end;
                if (!continuation.closingHook) continue;
                result.set(i, new Mark(mark.start, boundary, mark.passes, mark.text, mark.x,
                        mark.staffIndex, mark.staffCount, false, mark.precedingRoof));
                break;
            }
        }
        result.sort(Comparator.comparingInt(Mark::start).thenComparingInt(Mark::end));
        return List.copyOf(result);
    }

    private static boolean adjacentSecondEnding(Continuation continuation, List<Mark> markings) {
        int matches = 0;
        for (var mark : markings) {
            float distance = mark.x - continuation.right;
            if (mark.start == continuation.end && mark.passes.equals(List.of(2))
                    && mark.staffIndex == continuation.staffIndex
                    && mark.staffCount == continuation.staffCount
                    && distance >= 0 && distance <= continuation.joinAllowance) matches++;
        }
        return matches == 1;
    }

    private static int openingBoundary(
            List<MeasureRegion> measures, PlayingTechniqueDetector.Staff staff,
            int x, int roofRight, int width, int height) {
        float center = (staff.top() + staff.bottom()) * .5f / height;
        float tolerance = staff.gap() / height;
        float left = Float.POSITIVE_INFINITY;
        int first = -1;
        for (int i = 0; i < measures.size(); i++) {
            var measure = measures.get(i);
            if (center < measure.top() - tolerance || center > measure.bottom() + tolerance
                    || measure.left() >= left) continue;
            left = measure.left();
            first = i;
        }
        float distance = left * width - x;
        if (first < 0 || distance < 0) return -1;
        if (distance <= staff.gap() * 4.65f) return first;
        // Some roofs begin above the clef, well before the first playable bar.
        // The longer reach is valid only when the same roof covers that whole bar.
        return distance <= staff.gap() * 7
                && roofRight >= measures.get(first).right() * width - staff.gap() * 2.5f
                ? first : -1;
    }

    private static boolean rowLast(
            List<MeasureRegion> measures, PlayingTechniqueDetector.Staff staff, int bar, int height) {
        if (bar + 1 == measures.size()) return true;
        var next = measures.get(bar + 1);
        float y = (staff.top() + staff.bottom()) * .5f / height;
        return y < next.top() - staff.gap() / height;
    }

    private static boolean hasPairedSelector(EndingSpan span, List<EndingSpan> spans, float gap) {
        int matches = 0;
        for (var other : spans) {
            if (other == span || Math.abs(span.roof - other.roof) > gap * .3f) continue;
            var first = span.left < other.left ? span : other;
            var second = first == span ? other : span;
            if (!first.mark.openRight && first.mark.passes.equals(List.of(1))
                    && second.mark.passes.equals(List.of(2)) && first.mark.end == second.mark.start
                    && second.left >= first.right && second.left - first.right <= gap * 1.1f)
                matches++;
        }
        return matches == 1;
    }

    /** Adjacent numbered brackets can use plain digits with no printed period. */
    private static PlayingTechniqueDetector.Word plainSelector(
            byte[] gray, int width, int height, int left, int right,
            double roofY, double slope, int hook, float gap) {
        int x0 = left + Math.max(2, Math.round(.25f * gap));
        int x1 = Math.min(right - 1, left + Math.round(2.8f * gap));
        int y0 = Math.max(0, (int) Math.ceil(roofY + Math.max(0, slope * (x1 - left)) + gap * .28f));
        int y1 = Math.min(height - 1, (int) Math.floor(roofY + Math.min(2.8f * gap, hook + gap * .55f)));
        if (x1 <= x0 || y1 <= y0 || (long) (x1 - x0 + 1) * (y1 - y0 + 1) > 16_384)
            return null;
        var graph = RawNumeralComponents.capture(
                gray, width, height, x0, y0, x1, y1, () -> {}, () -> {});
        RawNumeralComponents.Component digit = null;
        for (var component : graph.components()) {
            if (component.size() < Math.max(3, gap * gap * .022f)) continue;
            if (digit != null || component.left == x0 || component.right == x1
                    || component.top == y0 || component.bottom == y1) return null;
            digit = component;
        }
        if (digit == null || digit.left - left > gap || digit.top - roofY > gap * 1.12f)
            return null;
        int w = digit.right - digit.left + 1, h = digit.bottom - digit.top + 1;
        if (h < .83f * gap || h > 1.75f * gap || w < .23f * gap || w > 1.12f * gap)
            return null;
        var reading = JoinedDigitTemplates.inspect(gray, width, height, new BitSet(),
                digit.left, digit.top, digit.right, digit.bottom, () -> {}, true);
        if (reading == null) return null;
        int value;
        if (w <= .46f * h && reading.scores()[1] >= .52f
                && reading.scores()[1] - reading.runner() >= .12f && slenderOne(digit, width)) {
            value = 1;
        } else if (reading.digit() == 2 && reading.best() >= .62f
                && reading.best() - reading.runner() >= .15f) {
            value = 2;
        } else return null;
        return new PlayingTechniqueDetector.Word(Integer.toString(value),
                digit.left / (float) width, digit.top / (float) height,
                (digit.right + 1) / (float) width, (digit.bottom + 1) / (float) height);
    }

    private static int joinedChordSelector(
            List<PlayingTechniqueDetector.Word> words, int width, int height,
            int left, double roof, float gap) {
        int value = 0;
        for (var word : words) {
            if (word.left() * width < left - gap || word.left() * width > left + gap * 2
                    || word.top() * height < roof - gap || word.top() * height > roof + gap * 2
                    || word.right() * width > left + gap * 7) continue;
            var match = SELECTOR_CHORD.matcher(word.text().strip());
            if (!match.matches()) continue;
            int found = Integer.parseInt(match.group(1));
            if (value != 0 && value != found) return 0;
            value = found;
        }
        return value;
    }

    /** A missing page OCR token can be recovered from a digit and its independent period. */
    private static PlayingTechniqueDetector.Word isolatedSelector(
            byte[] gray, int width, int height, int left, int right,
            double roofY, double slope, int hook, float gap,
            List<PlayingTechniqueDetector.Word> words, int chordSelector) {
        int cropLeft = left + Math.max(2, Math.round(.16f * gap));
        int cropRight = Math.min(right - 1, left + Math.round(3 * gap));
        int top = Math.max(0, (int) Math.ceil(roofY + Math.max(0, slope * (cropRight - left))) + 1);
        int bottom = Math.min(height - 1, (int) Math.floor(roofY + Math.min(3.15f * gap, hook + .65f * gap)));
        if (cropLeft > cropRight || top > bottom
                || (long) (cropRight - cropLeft + 1) * (bottom - top + 1) > 16_384) return null;
        var graph = RawNumeralComponents.capture(gray, width, height, cropLeft, top, cropRight, bottom,
                () -> {}, () -> {});
        PlayingTechniqueDetector.Word candidate = null;
        for (var digit : graph.components()) {
            int glyphHeight = digit.bottom - digit.top + 1;
            int glyphWidth = digit.right - digit.left + 1;
            if (digit.left - left > 1.4f * gap || digit.right == cropRight
                    || glyphHeight < .72f * gap || glyphHeight > 2.2f * gap
                    || glyphWidth < .22f * gap || glyphWidth > 1.2f * gap) continue;
            for (var dot : graph.components()) {
                float dotGap = dot.left - digit.right;
                if (dotGap <= 0 || dotGap > .70f * gap || dot.right == cropRight
                        || dot.right - dot.left + 1 > Math.ceil(.45f * gap)
                        || dot.bottom - dot.top + 1 > Math.ceil(.35f * gap)
                        || Math.abs(dot.bottom - digit.bottom) > .24f * gap) continue;
                // A separator in a multi-pass selector must never become a terminal period.
                var followingInk = graph.components().stream().filter(other -> other.left > dot.right
                        && other.left - dot.right <= gap
                        && other.bottom - other.top + 1 >= .72f * gap).toList();
                if (!followingInk.isEmpty() && chordSelector == 0
                        && words.stream().noneMatch(word ->
                                ownsFollowingChord(word, followingInk, dot.right, width, height, gap)))
                    continue;
                var reading = JoinedDigitTemplates.inspect(gray, width, height, new BitSet(),
                        digit.left, digit.top, digit.right, digit.bottom, () -> {}, true);
                if (reading == null) continue;
                boolean provedOne = glyphWidth <= .50f * glyphHeight
                        && reading.scores()[1] >= .50f
                        && reading.scores()[1] - reading.runner() >= .12f
                        && slenderOne(digit, width);
                // A compressed serif 1 can be too narrow for the digit templates.
                provedOne |= reading.digit() < 0 && glyphWidth <= .27f * glyphHeight
                        && glyphHeight >= gap && condensedOne(digit, gray, width);
                if (!provedOne && (reading.digit() < 1 || reading.best() < .65f
                        || reading.best() - reading.runner() < .12f)) continue;
                int value = provedOne ? 1 : reading.digit();
                if (!followingInk.isEmpty() && chordSelector != 0 && value != chordSelector) continue;
                if (candidate != null) return null;
                candidate = new PlayingTechniqueDetector.Word(value + ".",
                        digit.left / (float) width, digit.top / (float) height,
                        (dot.right + 1) / (float) width, (Math.max(dot.bottom, digit.bottom) + 1) / (float) height);
            }
        }
        return candidate;
    }

    private static boolean ownsFollowingChord(
            PlayingTechniqueDetector.Word word, List<RawNumeralComponents.Component> ink,
            int dotRight, int width, int height, float gap) {
        if (!SELECTOR_CHORD.matcher("1." + word.text().strip()).matches()) return false;
        float left = word.left() * width, right = word.right() * width;
        float top = word.top() * height, bottom = word.bottom() * height;
        if (left < dotRight - gap * .35f || left > dotRight + gap) return false;
        float tolerance = gap * .25f;
        return ink.stream().allMatch(component -> left - tolerance <= component.left
                && component.right < right + tolerance && top - tolerance <= component.top
                && component.bottom < bottom + tolerance);
    }

    private static boolean condensedOne(
            RawNumeralComponents.Component digit, byte[] gray, int width) {
        int height = digit.bottom - digit.top + 1;
        int cap = 0, foot = 0, middle = 0, stem = 0;
        for (int y = digit.top; y <= digit.bottom; y++) {
            int row = y - digit.top;
            if ((gray[y * width + digit.left + 1] & 255) < 150) stem++;
            if ((gray[y * width + digit.left] & 255) >= 150) continue;
            if (row < height * .25f) cap++;
            else if (row >= height * .75f) foot++;
            else middle++;
        }
        return stem >= height * .9f && cap >= 2 && foot >= 2 && middle <= height * .1f;
    }

    private static boolean slenderOne(RawNumeralComponents.Component glyph, int width) {
        int height = glyph.bottom - glyph.top + 1;
        int[] columns = new int[glyph.right - glyph.left + 1];
        for (int i = 0; i < glyph.size(); i++) columns[glyph.pixel(i) % width - glyph.left]++;
        int stemLeft = columns.length, stemRight = -1;
        for (int x = 0; x < columns.length; x++) {
            if (columns[x] < .8f * height) continue;
            stemLeft = Math.min(stemLeft, x);
            stemRight = x;
        }
        if (stemLeft == 0 || stemRight < stemLeft || stemRight == columns.length - 1) return false;
        boolean cap = false, leftFoot = false, rightFoot = false;
        for (int i = 0; i < glyph.size(); i++) {
            int pixel = glyph.pixel(i);
            int x = pixel % width - glyph.left, y = pixel / width - glyph.top;
            cap |= x < stemLeft && y < .3f * height;
            leftFoot |= x < stemLeft && y >= .75f * height;
            rightFoot |= x > stemRight && y >= .75f * height;
        }
        return cap && leftFoot && rightFoot;
    }

    static List<Integer> passes(String text) {
        if (text == null) return List.of();
        text = text.trim();
        if (text.isEmpty() || text.charAt(0) < '0' || text.charAt(0) > '9'
                || !SELECTOR.matcher(text).matches()) return List.of();
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
