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
        var found = dots(gray, width, top, bottom, gap, new DotScratch());
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
            var dots = dots(gray, width, top, bottom, gap, scratch);
            var neighbors = new DotNeighbors(dots, gap);
            for (var a : dots)
                for (int index = neighbors.first(a); index >= 0; index = neighbors.next()) {
                    var b = dots.get(index);
                    if (Math.abs(a.x - b.x) > gap * .2f
                            || b.y - a.y < gap * .7f
                            || b.y - a.y > gap * 1.3f) continue;
                    float cx = (a.x + b.x) * .5f, cy = (a.y + b.y) * .5f, measuredGap = b.y - a.y;
                    if (Math.abs(cy - (staff.top() + staff.bottom()) * .5f) > gap) continue;
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
                    if (mark != null && !result.contains(mark)) result.add(mark);
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
        int flank = Math.max(2, Math.round(gap * .3f)), band = Math.max(1, Math.round(gap * .2f));
        int firstStep = Math.round(gap * 2), lastStep = Math.round(gap * 6);
        int samples = lastStep - firstStep + 1;
        if (samples < 8) return false;
        int xStart = Math.round(cx - barSide * firstStep);
        int xEnd = Math.round(cx - barSide * lastStep);
        if (Math.min(xStart, xEnd) < 0 || Math.max(xStart, xEnd) >= width) return false;
        int required = (int) Math.ceil(samples * .7f);
        int flankOffset = flank * width;
        for (int line = 0; line < 5; line++) {
            int center = Math.round(cy + (line - 2) * gap);
            if (center - band - flank < 0 || center + band + flank >= height) return false;
            int need = required;
            int low = (center - band) * width, high = (center + band) * width;
            for (int dx = firstStep; need > 0 && need <= lastStep - dx + 1; dx++) {
                int x = Math.round(cx - barSide * dx);
                for (int p = low + x; p <= high + x; p += width) {
                    int tone = gray[p] & 255;
                    if (tone < 220
                            && (gray[p - flankOffset] & 255) > tone + 15
                            && (gray[p + flankOffset] & 255) > tone + 15) {
                        need--;
                        break;
                    }
                }
            }
            if (need > 0) return false;
        }
        return true;
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
        return result;
    }

    private static List<Dot> dots(
            byte[] gray, int width, int top, int bottom, float gap, DotScratch scratch) {
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
            if ((gray[offset + seed] & 255) >= 140) {
                if (seed <= bandSize - Long.BYTES) {
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
                        && (gray[offset + row + runLeft - 1] & 255) < 140) runLeft--;
                while (runRight + 1 < width && !seen[row + runRight + 1]
                        && (gray[offset + row + runRight + 1] & 255) < 140) runRight++;
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
                        if (seen[next] || (gray[offset + next] & 255) >= 140) continue;
                        seen[next] = true;
                        if (queued == queue.length)
                            scratch.queue = queue =
                                    Arrays.copyOf(queue, queued + Math.min(queued, bandSize - queued));
                        queue[queued++] = next;
                        while (next < end && !seen[next + 1]
                                && (gray[offset + next + 1] & 255) < 140) next++;
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
