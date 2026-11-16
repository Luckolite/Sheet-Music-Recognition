// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;

/** Conservative clearance for optional engraving on an independently proved primary-beam triplet. */
final class UnnumberedTripletInk {
    private UnnumberedTripletInk() {}

    record Rows(int first, int last) {}

    /** A nearby system can place strokes inside an otherwise plausible search window. */
    static Rows sourceRows(MeasureRegion bar, List<MeasureRegion> page, int height) {
        float first = 0, last = 1;
        for (var region : page) {
            if (region.left() >= bar.right() || region.right() <= bar.left()) continue;
            if (region.bottom() < bar.top())
                first = Math.max(first, (region.bottom() + bar.top()) * .5f);
            if (region.top() > bar.bottom())
                last = Math.min(last, (region.top() + bar.bottom()) * .5f);
        }
        return new Rows((int)Math.ceil(first * height), Math.min(height - 1, (int)Math.floor(last * height)));
    }

    static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts) {
        return clear(ink, width, height, gap, headsX, headsY, shafts, new Rows(0, height - 1));
    }

    static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows) {
        if (ink == null || width < 1 || height < 1 || ink.length != (long) width * height
                || !Float.isFinite(gap) || gap < 10 || gap > 25
                || headsX.length != 3 || headsY.length != 3 || shafts.size() != 3) return false;
        for (int i = 0; i < 3; i++)
            if (!Float.isFinite(headsX[i]) || !Float.isFinite(headsY[i])
                    || i > 0 && headsX[i - 1] >= headsX[i]) return false;
        var first = shafts.get(0);
        var last = shafts.get(2);
        int travel = first.direction();
        if (Math.abs(travel) != 1 || shafts.stream().anyMatch(s -> s.direction() != travel)) return false;
        float lo = (first.left() + first.right()) * .5f;
        float hi = (last.left() + last.right()) * .5f;
        if (hi - lo < gap * 2.8f || hi - lo > gap * 5.5f) return false;
        float topHead = Math.min(headsY[0], Math.min(headsY[1], headsY[2]));
        float bottomHead = Math.max(headsY[0], Math.max(headsY[1], headsY[2]));
        float top = Math.min(topHead, shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).min().orElseThrow());
        float bottom = Math.max(bottomHead, shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).max().orElseThrow());
        int left = Math.round(lo - gap), right = Math.round(hi + gap);
        int start = Math.round(top - gap * 7), end = Math.round(bottom + gap * 7);
        int tailRadius = Math.round(gap * 2.5f);
        if (left - tailRadius < 0 || right + tailRadius >= width || start < 0 || end >= height) return false;
        var window = new Window(ink, width, left, start, right - left + 1, end - start + 1);
        boolean[] staff = window.staffRows(tailRadius, gap);
        for (int x = Math.round(lo + .15f * (hi - lo)); x <= Math.round(lo + .85f * (hi - lo)); x++) {
            float beam = first.endY() + (last.endY() - first.endY()) * (x - lo) / (hi - lo);
            boolean joined = true;
            for (int margin = Math.round(.25f * gap); margin <= Math.round(.78f * gap); margin++)
                if (!window.dark(x, Math.round(beam) + travel * margin, staff)) {
                    joined = false;
                    break;
                }
            if (joined) return false;
        }
        if (window.bracket(staff, Math.min(lo, headsX[0]), Math.max(hi, headsX[2]),
                topHead, bottomHead, top, bottom, gap, sourceRows)) return false;
        int cells = window.width * window.height;
        boolean[] used = new boolean[cells];
        int[] queue = new int[cells];
        for (int seed = 0; seed < cells; seed++) {
            int x = seed % window.width + left, y = seed / window.width + start;
            if (used[seed] || !window.dark(x, y, staff)) continue;
            used[seed] = true;
            queue[0] = seed;
            int size = 1, minX = x, minY = y, maxX = x, maxY = y;
            for (int i = 0; i < size; i++) {
                int px = queue[i] % window.width + left, py = queue[i] / window.width + start;
                minX = Math.min(minX, px);
                minY = Math.min(minY, py);
                maxX = Math.max(maxX, px);
                maxY = Math.max(maxY, py);
                for (int yy = Math.max(start, py - 1); yy <= Math.min(end, py + 1); yy++)
                    for (int xx = Math.max(left, px - 1); xx <= Math.min(right, px + 1); xx++) {
                        int next = (yy - start) * window.width + xx - left;
                        if (used[next] || !window.dark(xx, yy, staff)) continue;
                        used[next] = true;
                        queue[size++] = next;
                    }
            }
            if (maxY < sourceRows.first || minY > sourceRows.last
                    || size < Math.max(3, Math.round(.35f * gap))) continue;
            float center = (minX + maxX) * .5f;
            float fraction = (center - lo) / (hi - lo);
            if (fraction < .15f || fraction > .85f) continue;
            float beam = first.endY() + (last.endY() - first.endY()) * fraction;
            float beamSide = travel > 0 ? minY - beam : beam - maxY;
            float headSide = travel > 0 ? topHead - maxY : minY - bottomHead;
            boolean markBand = beamSide >= gap * .18f && beamSide <= gap * 1.8f;
            boolean headBand = headSide >= gap * .95f && headSide <= gap * 5f;
            if (!(markBand || headBand)) continue;
            int inkWidth = maxX - minX + 1, inkHeight = maxY - minY + 1;
            if (markBand && ownedReturningCurve(queue, size, window, minX, maxX, shafts, travel, gap))
                continue;
            if (inkWidth > .58f * gap || inkHeight > .58f * gap || size < .74f * inkWidth * inkHeight)
                return false;
            boolean dot = false;
            for (int i = 0; i < 3; i++) {
                var shaft = shafts.get(i);
                float ownerX = markBand ? (shaft.left() + shaft.right()) * .5f : headsX[i];
                if (Math.abs(ownerX - center) <= .3f * gap) dot = true;
            }
            if (!dot) return false;
        }
        return true;
    }

    private static boolean ownedReturningCurve(int[] pixels, int size, Window window, int left, int right,
            List<PrintedBeamShafts.Shaft> shafts, int travel, float gap) {
        if (right - left < gap * 2.1f || right - left > gap * 3f) return false;
        boolean owned = false;
        for (int i = 0; i < 2; i++) {
            var a = shafts.get(i);
            var b = shafts.get(i + 1);
            if (Math.abs(left - (a.left() + a.right()) * .5f) <= gap * .38f
                    && Math.abs(right - (b.left() + b.right()) * .5f) <= gap * .38f) owned = true;
        }
        if (!owned) return false;
        int length = right - left + 1;
        int[] low = new int[length], high = new int[length], counts = new int[length];
        Arrays.fill(low, Integer.MAX_VALUE);
        Arrays.fill(high, -1);
        for (int i = 0; i < size; i++) {
            int x = pixels[i] % window.width + window.left - left;
            int y = pixels[i] / window.width;
            if (travel > 0) y = window.height - 1 - y;
            low[x] = Math.min(low[x], y);
            high[x] = Math.max(high[x], y);
            counts[x]++;
        }
        for (int i = 0; i < length; i++)
            if (counts[i] == 0 || high[i] - low[i] + 1 != counts[i])
                return false;
        for (int i = 0; i < length; i++) {
            int before = Math.max(0, i - 2), after = Math.min(length - 1, i + 2);
            float slope = (high[after] + low[after] - high[before] - low[before])
                    / (2f * (after - before));
            if (counts[i] > gap * .36f * Math.hypot(1, slope)) return false;
        }
        return PrintedSlurContours.completeReturningProfile(low, high, 0, length - 1, gap);
    }

    private record Window(byte[] plane, int stride, int left, int top, int width, int height) {
        private boolean dark(int x, int y, boolean[] staff) {
            return !staff[y - top] && (plane[y * stride + x] & 255) < 205;
        }

        private boolean[] staffRows(int tail, float gap) {
            boolean[] broad = new boolean[height];
            int end = left + width - 1 + tail;
            int leftMargin = left - tail;
            int tailLength = Math.round(1.6f * gap);
            for (int row = 0; row < height; row++) {
                int count = 0, leftTail = 0, rightTail = 0;
                for (int x = leftMargin; x <= end; x++) {
                    if ((plane[(top + row) * stride + x] & 255) >= 200) continue;
                    count++;
                    if (x < leftMargin + tailLength) leftTail++;
                    if (x > end - tailLength) rightTail++;
                }
                broad[row] = count >= .92f * (end - leftMargin + 1)
                        && leftTail >= .90f * tailLength && rightTail >= .90f * tailLength;
            }
            boolean[] result = new boolean[height];
            for (int row = 0; row < height; row++) {
                int consecutive = 0;
                if (broad[row]) {
                    int first = row, last = row;
                    while (first > 0 && broad[first - 1]) first--;
                    while (last + 1 < height && broad[last + 1]) last++;
                    consecutive = last - first + 1;
                }
                result[row] = consecutive > 0 && consecutive <= Math.round(.25f * gap);
            }
            return result;
        }

        private boolean bracket(boolean[] staff, float start, float end, float headTop,
                float headBottom, float upper, float lower, float gap, Rows sourceRows) {
            float middle = (start + end) * .5f;
            int a = Math.round(start - gap * .32f), b = Math.round(middle - gap * .72f);
            int c = Math.round(middle + gap * .72f), d = Math.round(end + gap * .32f);
            int pad = Math.max(6, Math.round(.12f * Math.max(middle - a, d - middle)) + 1);
            int first = Math.max(sourceRows.first, Math.max(top + pad, Math.round(headTop - 7 * gap)));
            int last = Math.min(sourceRows.last, Math.min(top + height - pad - 1, Math.round(headBottom + 7 * gap)));
            for (int y = first; y <= last; y++) {
                if (y > upper - gap * .48f && y < lower + gap * .48f) continue;
                for (int slope = -2; slope <= 2; slope++)
                    if (arm(staff, y, middle, slope * .06f, a, b)
                            && arm(staff, y, middle, slope * .06f, c, d))
                        return true;
            }
            return false;
        }

        private boolean arm(boolean[] staff, int y, float center, float slope, int left, int right) {
            int ink = 0;
            for (int x = left; x <= right; x++)
                if (dark(x, y + Math.round(slope * (x - center)), staff)) ink++;
            return ink >= .63f * (right - left + 1);
        }
    }
}
