// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Printed repeat candidates require isolated paired dots and ordered thin/thick full staff bars. */
final class PrintedRepeatGlyphs {
    record Mark(int boundary, boolean outgoing, float x, int staffIndex, int staffCount) {}

    private record Dot(float x, float y) {}

    private record Bar(int left, int right) {
        float center() {
            return (left + right) * .5f;
        }

        int width() {
            return right - left + 1;
        }
    }

    private static final class DotScratch {
        boolean[] seen;
        int[] queue;
    }

    private static final java.lang.invoke.VarHandle PIXEL_WORDS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                    long[].class, java.nio.ByteOrder.nativeOrder());
    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long DOT_BIAS = 0x0101010101010101L * (140 - 128);

    private static final int[] JOINED_DOT_CORE_THRESHOLDS = {110, 35};

    private PrintedRepeatGlyphs() {}

    /** Raw repeat proof used before semantic notehead fragments can veto its rails. */
    static BitSet provedColumns(
            byte[] gray, int width, int height, float staffTop, float staffBottom, float gap) {
        BitSet result = new BitSet();
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || !Float.isFinite(gap)
                || gap < 5
                || gap > Math.min(width, height) / 8f) return result;
        int top = Math.max(0, Math.round(staffTop - gap)),
                bottom = Math.min(height - 1, Math.round(staffBottom + gap));
        if (bottom <= top) return result;
        var found = dotsWithDarkCores(gray, width, top, bottom, gap, new DotScratch());
        var neighbors = new DotNeighbors(found, gap);
        for (var a : found)
            for (int index = neighbors.first(a); index >= 0; index = neighbors.next()) {
                var b = found.get(index);
                if (Math.abs(a.x - b.x) > gap * .2f
                        || b.y - a.y < gap * .7f
                        || b.y - a.y > gap * 1.3f) continue;
                float cx = (a.x + b.x) * .5f, cy = (a.y + b.y) * .5f, measuredGap = b.y - a.y;
                if (Math.abs(cy - (staffTop + staffBottom) * .5f) > gap) continue;
                // Either flank can prove the staff rules for both bar sides.
                if (!staffRails(gray, width, height, cx, cy, measuredGap, -1)
                        && !staffRails(gray, width, height, cx, cy, measuredGap, 1)) continue;
                var left = bars(gray, width, height, cx, cy, measuredGap, gap, -1);
                var right = bars(gray, width, height, cx, cy, measuredGap, gap, 1);
                boolean leftProved = repeatInner(left, cx, gap) != null;
                boolean rightProved = repeatInner(right, cx, gap) != null;
                if (leftProved != rightProved) {
                    for (var bar : leftProved ? left : right) result.set(bar.left, bar.right + 1);
                }
            }
        return result;
    }

    private record DotPair(float x, float y, float spacing) {}

    /** Four independently printed dots distinguish a shared heavy/heavy repeat from a double bar. */
    static List<Float> sharedRepeatCenters(byte[] gray, int width, int height,
            float staffTop, float staffBottom, float gap) {
        if (gray == null || width <= 0 || height <= 0 || gray.length != (long) width * height
                || !Float.isFinite(gap) || gap < 6 || gap > Math.min(width, height) / 8f)
            return List.of();
        int top = Math.max(0, Math.round(staffTop - gap));
        int bottom = Math.min(height - 1, Math.round(staffBottom + gap));
        if (top >= bottom) return List.of();
        return sharedRepeatCenters(gray, width, height, (staffTop + staffBottom) * .5f, gap,
                dotsWithDarkCores(gray, width, top, bottom, gap, new DotScratch()));
    }

    private static List<Float> sharedRepeatCenters(byte[] gray, int width, int height,
            float middle, float gap, List<Dot> dots) {
        var pairs = new ArrayList<DotPair>();
        for (var a : dots) {
            if (Math.abs(a.y - middle) > gap * 1.4f) continue;
            for (var b : dots) {
                if (!alignedDots(a, b, 1, gap)) continue;
                float y = (a.y + b.y) * .5f;
                if (Math.abs(y - middle) <= gap * .6f)
                    pairs.add(new DotPair((a.x + b.x) * .5f, y, b.y - a.y));
            }
        }
        var centers = new ArrayList<Float>();
        for (var left : pairs) {
            for (var right : pairs) {
                float spread = right.x - left.x;
                if (spread < gap * 1.8f || spread > gap * 3.4f
                        || Math.abs(right.y - left.y) > gap * .22f
                        || Math.abs(right.spacing - left.spacing) > gap * .22f) continue;
                var leftRails = bars(gray, width, height, left.x, left.y, left.spacing, gap, 1);
                var rightRails = bars(gray, width, height, right.x, right.y, right.spacing, gap, -1);
                if (leftRails.size() != 2 || rightRails.size() != 2) continue;
                var a = leftRails.get(0);
                var b = leftRails.get(1);
                if (Math.abs(a.center() - rightRails.get(0).center()) > gap * .2f
                        || Math.abs(b.center() - rightRails.get(1).center()) > gap * .2f
                        || a.width() < gap * .43f || b.width() < gap * .43f
                        || a.width() > gap * .88f || b.width() > gap * .88f
                        || Math.abs(a.width() - b.width()) > gap * .22f
                        || b.left - a.right - 1 < 1 || b.left - a.right - 1 > gap * .35f
                        || a.left - left.x < gap * .30f || right.x - b.right < gap * .30f)
                    continue;
                if ((!staffRails(gray, width, height, left.x, left.y, left.spacing, 1)
                                && !staffRails(gray, width, height, left.x, left.y, left.spacing, -1))
                        || (!staffRails(gray, width, height, right.x, right.y, right.spacing, -1)
                                && !staffRails(gray, width, height, right.x, right.y, right.spacing, 1)))
                    continue;
                float x = (a.right + b.left) * .5f;
                if (centers.stream().noneMatch(old -> Math.abs(old - x) < gap * .4f))
                    centers.add(x);
            }
        }
        return centers;
    }

    static List<Mark> detect(
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || staffs == null
                || measures == null) return List.of();
        var result = new ArrayList<Mark>();
        var scratch = new DotScratch();
        for (var staff : staffs) {
            float gap = staff.gap();
            if (!Float.isFinite(gap) || gap < 5 || gap > Math.min(width, height) / 8f) continue;
            int top = Math.max(0, Math.round(staff.top() - gap));
            int bottom = Math.min(height - 1, Math.round(staff.bottom() + gap));
            if (bottom <= top) continue;
            StaffPitchTrack track = null;
            boolean checkedTrack = false;
            var dots = dotsWithDarkCores(gray, width, top, bottom, gap, scratch);
            for (float x : sharedRepeatCenters(gray, width, height,
                    (staff.top() + staff.bottom()) * .5f, gap, dots)) {
                int outgoing = boundary(measures, staff, x, true, width, height);
                int incoming = boundary(measures, staff, x, false, width, height);
                if (outgoing == incoming && outgoing > 0 && outgoing < measures.size()) {
                    result.add(new Mark(outgoing, true, x / width, staff.index(), staff.count()));
                    result.add(new Mark(outgoing, false, x / width, staff.index(), staff.count()));
                }
            }
            var neighbors = new DotNeighbors(dots, gap);
            for (var a : dots)
                for (int index = neighbors.first(a); index >= 0; index = neighbors.next()) {
                    var b = dots.get(index);
                    if (Math.abs(a.x - b.x) > gap * .2f
                            || b.y - a.y < gap * .7f
                            || b.y - a.y > gap * 1.3f) continue;
                    float cx = (a.x + b.x) * .5f, cy = (a.y + b.y) * .5f, measuredGap = b.y - a.y;
                    float midpointDistance = Math.abs(cy - (staff.top() + staff.bottom()) * .5f);
                    if (midpointDistance > gap * 2.3f) continue;
                    // Either flank can prove the staff rules for both bar sides.
                    if (!staffRails(gray, width, height, cx, cy, measuredGap, -1)
                            && !staffRails(gray, width, height, cx, cy, measuredGap, 1)) continue;
                    Mark mark = null;
                    for (int side = -1; side <= 1; side += 2) {
                        var inner = repeatInner(
                                bars(gray, width, height, cx, cy, measuredGap, gap, side), cx, gap);
                        if (inner == null) continue;
                        int boundary = boundary(measures, staff, inner.center(), side > 0, width, height);
                        if (boundary < 0) continue;
                        if (mark != null) {
                            mark = null;
                            break;
                        }
                        mark = new Mark(boundary, side > 0, inner.center() / width,
                                staff.index(), staff.count());
                    }
                    if (mark == null || result.contains(mark)) continue;
                    // A long slanted staff can place the repeat dots beyond the flat seed.
                    // Reassign only after the glyph and a local five-rule track agree.
                    if (midpointDistance > gap) {
                        if (!checkedTrack) {
                            track = StaffPitchTrack.detectForMeasures(
                                    gray, width, height, staff.top(), staff.bottom(), gap);
                            checkedTrack = true;
                        }
                        if (track == null || !track.activeAt(cx)) continue;
                        var local = track.at(cx);
                        if (Math.abs(cy - (local[0] - 2 * local[1])) > local[1] * .4f
                                || Math.abs(measuredGap - local[1]) > local[1] * .28f) continue;
                    }
                    result.add(mark);
                }
        }
        result.sort(Comparator.comparingInt(Mark::boundary).thenComparing(Mark::outgoing));
        return List.copyOf(result);
    }

    /** Merge nearby column buckets in the original dot order. */
    private static final class DotNeighbors {
        private final int[] heads, links;
        private final double bucketWidth;
        private int left, center, right;

        DotNeighbors(List<Dot> dots, float gap) {
            bucketWidth = 2d * (gap * .2f);
            int lastBucket = 0;
            if (dots.size() >= 32) {
                for (Dot dot : dots) lastBucket = Math.max(lastBucket, bucket(dot));
            }
            // Small or very sparse inputs cost less to scan than to index.
            if (dots.size() < 32 || lastBucket >= 8L * dots.size()) {
                heads = null;
                links = null;
                right = dots.size();
                return;
            }
            heads = new int[lastBucket + 2];
            Arrays.fill(heads, -1);
            links = new int[dots.size()];
            for (int i = dots.size() - 1; i >= 0; i--) {
                int b = bucket(dots.get(i));
                links[i] = heads[b];
                heads[b] = i;
            }
        }

        private int bucket(Dot dot) {
            return (int) (dot.x / bucketWidth);
        }

        int first(Dot dot) {
            if (heads == null) {
                center = 0;
            } else {
                int b = bucket(dot);
                left = b == 0 ? -1 : heads[b - 1];
                center = heads[b];
                right = heads[b + 1];
            }
            return next();
        }

        int next() {
            if (heads == null) return center < right ? center++ : -1;
            int index = center;
            if (left >= 0 && (index < 0 || left < index)) index = left;
            if (right >= 0 && (index < 0 || right < index)) index = right;
            if (index >= 0) {
                if (index == left) left = links[index];
                else if (index == center) center = links[index];
                else right = links[index];
            }
            return index;
        }
    }

    private static Bar repeatInner(List<Bar> pair, float center, float gap) {
        if (pair.size() != 2) return null;
        Bar first = pair.get(0), second = pair.get(1);
        boolean firstIsInner = Math.abs(first.center() - center) <= Math.abs(second.center() - center);
        Bar inner = firstIsInner ? first : second;
        Bar outer = firstIsInner ? second : first;
        if (inner.width() > gap * .35f
                || outer.width() < Math.max(2, inner.width() * 1.5f)
                || outer.width() > gap * .85f) return null;
        return inner;
    }

    private static boolean staffRails(
            byte[] gray, int width, int height, float cx, float cy, float gap, int barSide) {
        if (staffRails(gray, width, height, cx, cy, gap, barSide,
                Math.round(gap * 2), Math.round(gap * 6))) return true;

        // At a system edge, a slur or note can cover the distant rail samples.
        // Restrict the near-flank proof to a complete pair with blank exterior.
        var terminalBars = bars(gray, width, height, cx, cy, gap, gap, barSide);
        Bar inner = repeatInner(terminalBars, cx, gap);
        if (inner == null) return false;
        Bar outer = terminalBars.get(barSide > 0 ? 1 : 0);

        int edge = barSide > 0 ? outer.right() : outer.left();
        int begin = Math.round(gap * .65f), end = Math.round(gap * 1.65f);
        if (edge + barSide * end < 0 || edge + barSide * end >= width
                || cy - 2 * gap - 3 < 0 || cy + 2 * gap + 3 >= height) return false;
        for (int line = -2; line <= 2; line++) {
            int y = Math.round(cy + line * gap);
            int ink = 0;
            for (int offset = begin; offset <= end; offset++) {
                int x = edge + barSide * offset;
                for (int py = y - 2; py <= y + 2; py++)
                    if ((gray[py * width + x] & 255) < 180) ink++;
            }
            if (ink > (end - begin + 1) * .3f) return false;
        }
        return staffRails(gray, width, height, cx, cy, gap, barSide,
                Math.round(gap * .70f), Math.round(gap * 2.5f));
    }

    private static boolean staffRails(
            byte[] gray, int width, int height, float cx, float cy, float gap, int barSide,
            int firstStep, int lastStep) {
        int flank = Math.max(2, Math.round(gap * .3f)), band = Math.max(1, Math.round(gap * .2f));
        int samples = lastStep - firstStep + 1;
        if (samples < 8) return false;
        int xStart = Math.round(cx - barSide * firstStep);
        int xEnd = Math.round(cx - barSide * lastStep);
        if (Math.min(xStart, xEnd) < 0 || Math.max(xStart, xEnd) >= width) return false;
        int minimum = (int) Math.ceil(samples * .64f);
        int required = (int) Math.ceil(samples * .7f);
        int cap = (int) Math.ceil(samples * .85f);
        int weak = 0, support = 0;
        int flankOffset = flank * width;
        for (int line = 0; line < 5; line++) {
            int center = Math.round(cy + (line - 2) * gap);
            if (center - band - flank < 0 || center + band + flank >= height) return false;
            int hits = 0;
            int low = (center - band) * width, high = (center + band) * width;
            for (int dx = firstStep; dx <= lastStep && hits < cap; dx++) {
                if (hits + lastStep - dx + 1 < minimum) return false;
                int x = Math.round(cx - barSide * dx);
                for (int p = low + x; p <= high + x; p += width) {
                    int tone = gray[p] & 255;
                    if (tone < 220
                            && (gray[p - flankOffset] & 255) > tone + 15
                            && (gray[p + flankOffset] & 255) > tone + 15) {
                        hits++;
                        break;
                    }
                }
            }
            if (hits < minimum || hits < required && ++weak > 1) return false;
            support += hits;
        }
        // One faded rule can still belong to a staff with four well-supported neighbors.
        return weak == 0 || support >= 5 * samples * .77f;
    }

    static int boundary(
            List<MeasureRegion> measures,
            PlayingTechniqueDetector.Staff staff,
            float x,
            boolean outgoing,
            int width,
            int height) {
        int chosen = -1;
        float nearest = Float.MAX_VALUE;
        float center = (staff.top() + staff.bottom()) * .5f / height;
        for (int i = 0; i < measures.size(); i++) {
            var m = measures.get(i);
            if (center < m.top() - staff.gap() / height
                    || center > m.bottom() + staff.gap() / height) continue;
            float distance = Math.abs(x - (outgoing ? m.right() : m.left()) * width);
            // Expanded silent bars share a rectangle; outgoing belongs to the last logical bar.
            if (distance < nearest || outgoing && distance == nearest) {
                nearest = distance;
                chosen = i + (outgoing ? 1 : 0);
            }
        }
        return nearest <= staff.gap() * 2.5f ? chosen : -1;
    }

    private static ArrayList<Bar> bars(
            byte[] gray,
            int width,
            int height,
            float cx,
            float cy,
            float measuredGap,
            float gap,
            int side) {
        var result = new ArrayList<Bar>();
        int start = -1;
        int left = Math.max(0, Math.round(cx - gap * 2.5f)),
                right = Math.min(width - 1, Math.round(cx + gap * 2.5f));
        int top = Math.round(cy - 2 * measuredGap), bottom = Math.round(cy + 2 * measuredGap);
        if (top < 0 || bottom >= height) return result;
        int required = (int) Math.ceil((bottom - top + 1) * .8f);
        for (int x = left; x <= right + 1; x++) {
            boolean solid = false;
            if (x <= right && (x - cx) * side > gap * .35f) {
                int needed = required;
                for (int y = top, at = y * width + x;
                        needed > 0 && needed <= bottom - y + 1;
                        y++, at += width) {
                    if ((gray[at] & 255) < 170) needed--;
                }
                solid = needed == 0;
            }
            if (solid && start < 0) start = x;
            if (!solid && start >= 0) {
                result.add(new Bar(start, x - 1));
                start = -1;
            }
        }
        joinFadedOuterBar(gray, width, top, bottom, gap, side, result);
        return result;
    }

    /** A washed stripe inside the thick bar can split its dark core into two thin bands. */
    private static void joinFadedOuterBar(
            byte[] gray, int width, int top, int bottom, float gap, int side, List<Bar> bars) {
        if (bars.size() != 3) return;
        int outer = side < 0 ? 0 : 1;
        var left = bars.get(outer);
        var right = bars.get(outer + 1);
        int seam = right.left - left.right - 1;
        if (seam < 1 || seam > gap * .22f || right.right - left.left + 1 > gap * .85f)
            return;
        int required = (int) Math.ceil((bottom - top + 1) * .8f);
        for (int x = left.right + 1; x < right.left; x++) {
            int ink = 0;
            for (int y = top; y <= bottom; y++)
                if ((gray[y * width + x] & 255) < 195) ink++;
            if (ink < required) return;
        }
        bars.set(outer, new Bar(left.left, right.right));
        bars.remove(outer + 1);
    }

    private static List<Dot> dotsWithDarkCores(
            byte[] gray, int width, int top, int bottom, float gap, DotScratch scratch) {
        var visible = dots(gray, width, top, bottom, gap, scratch, 140);
        if (visible.isEmpty()) return visible;
        var result = new ArrayList<>(visible);
        // A shaded staff rule can join one dot to the bar in the ordinary mask. Recover
        // only a compact dark core beside an independently visible dot in the pair.
        float staffMiddle = (top + bottom) * .5f;
        int radius = Math.round(gap * .65f);
        int size = radius * 2 + 1;
        byte[] patch = new byte[size * size];
        var coreScratch = new DotScratch();
        for (var dot : visible) {
            if (Math.abs(dot.y - staffMiddle) > gap * 1.65f) continue;
            int left = Math.round(dot.x) - radius;
            if (left < 0 || left + size > width) continue;
            for (int direction = -1; direction <= 1; direction += 2) {
                int side = direction;
                if (result.stream().anyMatch(other -> alignedDots(dot, other, side, gap))) continue;
                int first = Math.round(dot.y + direction * gap) - radius;
                if (first < top || first + size - 1 > bottom) continue;
                for (int y = 0; y < size; y++)
                    System.arraycopy(gray, (first + y) * width + left, patch, y * size, size);
                for (int threshold : JOINED_DOT_CORE_THRESHOLDS) {
                    int before = result.size();
                    for (var core : dots(patch, size, 0, size - 1, gap, coreScratch, threshold)) {
                        if (Math.abs(core.x - radius) > gap * .3f) continue;
                        float x = left + core.x, y = first + core.y;
                        var mate = new Dot(x, y);
                        if (alignedDots(dot, mate, direction, gap)) result.add(mate);
                    }
                    if (result.size() != before) break;
                }
            }
        }
        return result;
    }

    private static boolean alignedDots(Dot dot, Dot other, int direction, float gap) {
        float space = (other.y - dot.y) * direction;
        return Math.abs(dot.x - other.x) <= gap * .2f && space >= gap * .7f && space <= gap * 1.3f;
    }

    private static List<Dot> dots(
            byte[] gray, int width, int top, int bottom, float gap, DotScratch scratch, int threshold) {
        int rows = bottom - top + 1, offset = top * width;
        int bandSize = width * rows;
        if (scratch.seen == null || scratch.seen.length < bandSize)
            scratch.seen = new boolean[bandSize];
        else Arrays.fill(scratch.seen, 0, bandSize, false);
        if (scratch.queue == null) scratch.queue = new int[Math.min(256, bandSize)];
        boolean[] seen = scratch.seen;
        int[] queue = scratch.queue;
        var result = new ArrayList<Dot>();
        for (int seed = 0; seed < bandSize; seed++) {
            if ((gray[offset + seed] & 255) >= threshold) {
                if (threshold == 140 && seed <= bandSize - Long.BYTES) {
                    long tones = (long) PIXEL_WORDS.get(gray, offset + seed);
                    if ((((tones | HIGH_BITS) - DOT_BIAS) & tones & HIGH_BITS) == HIGH_BITS)
                        seed += Long.BYTES - 1;
                }
                continue;
            }
            if (seen[seed]) continue;
            int take = 0, queued = 1, size = 0, left = width, right = -1;
            int first = seed / width, last = first;
            long sx = 0, sy = 0;
            queue[0] = seed;
            seen[seed] = true;
            while (take < queued) {
                int at = queue[take++], y = at / width, row = y * width;
                int runLeft = at - row, runRight = runLeft;
                while (runLeft > 0 && !seen[row + runLeft - 1]
                        && (gray[offset + row + runLeft - 1] & 255) < threshold) runLeft--;
                while (runRight + 1 < width && !seen[row + runRight + 1]
                        && (gray[offset + row + runRight + 1] & 255) < threshold) runRight++;
                Arrays.fill(seen, row + runLeft, row + runRight + 1, true);
                int count = runRight - runLeft + 1;
                size += count;
                sx += ((long) runLeft + runRight) * count / 2;
                sy += (long) y * count;
                left = Math.min(left, runLeft);
                right = Math.max(right, runRight);
                last = Math.max(last, y);
                int firstNeighbor = Math.max(0, runLeft - 1);
                int lastNeighbor = Math.min(width - 1, runRight + 1);
                for (int step = -1; step <= 1; step += 2) {
                    if (y + step < 0 || y + step >= rows) continue;
                    int neighbor = row + step * width;
                    int end = neighbor + lastNeighbor;
                    for (int next = neighbor + firstNeighbor; next <= end; next++) {
                        if (seen[next] || (gray[offset + next] & 255) >= threshold) continue;
                        seen[next] = true;
                        if (queued == queue.length)
                            scratch.queue = queue =
                                    Arrays.copyOf(queue, queued + Math.min(queued, bandSize - queued));
                        queue[queued++] = next;
                        while (next < end && !seen[next + 1]
                                && (gray[offset + next + 1] & 255) < threshold) next++;
                    }
                }
            }
            int w = right - left + 1, h = last - first + 1;
            if (first == 0
                    || last == rows - 1
                    || w < gap * .15f
                    || w > gap * .7f
                    || h < gap * .15f
                    || h > gap * .7f
                    || w / (float) h < .5f
                    || w / (float) h > 2
                    || size < w * h * .5f) continue;
            result.add(new Dot(sx / (float) size, top + sy / (float) size));
        }
        return result;
    }
}
