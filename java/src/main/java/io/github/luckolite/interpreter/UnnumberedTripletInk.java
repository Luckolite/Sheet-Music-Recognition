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

    /** Check the bracket separately when a numeral alone could be a fingering. */
    static boolean bracketBesideBeam(
            byte[] ink, int width, int height, float gap, float first, float last,
            float headTop, float headBottom, float shaftTip, Rows rows, StaffPitchTrack track) {
        return bracketBesideBeam(ink, width, height, gap, first, last,
                headTop, headBottom, shaftTip, rows, track, false);
    }

    static boolean bracketBesideBeam(
            byte[] ink, int width, int height, float gap, float first, float last,
            float headTop, float headBottom, float shaftTip, Rows rows, StaffPitchTrack track,
            boolean verifyContinuingStroke) {
        int margin = Math.round(2.5f * gap);
        int left = Math.round(first - gap), right = Math.round(last + gap);
        float upper = Math.min(headTop, shaftTip), lower = Math.max(headBottom, shaftTip);
        int top = Math.max(0, Math.round(upper - 7 * gap) - 12);
        int bottom = Math.min(height - 1, Math.round(lower + 7 * gap) + 12);
        if (left < margin || right + margin >= width) return true;
        var mask = trackedStaffMask(ink, width, height, left, right, top, bottom, track, gap);
        var window = new Window(ink, width, left, top, right - left + 1, bottom - top + 1, mask);
        return window.bracket(window.staffRows(margin, gap), first, last, headTop,
                lower, upper, lower, gap, rows, verifyContinuingStroke);
    }

    static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts) {
        return clear(ink, width, height, gap, headsX, headsY, shafts, new Rows(0, height - 1));
    }

    static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows) {
        return clear(ink, width, height, gap, headsX, headsY, shafts, sourceRows, null, List.of());
    }

    static boolean clearWithTrackedStaff(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staff) {
        return clearWithTrackedStaff(ink,width,height,gap,headsX,headsY,shafts,sourceRows,staff,List.of());
    }

    static boolean clearWithTrackedStaff(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staff, List<PlayingTechniqueDetector.Word> words) {
        return staff != null && clear(ink,width,height,gap,headsX,headsY,shafts,sourceRows,staff,words);
    }

    static boolean clearFollowing(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staffTrack, List<PlayingTechniqueDetector.Word> words, List<Head> lowerHeads) {
        return staffTrack != null && clear(ink, width, height, gap, headsX, headsY, shafts, sourceRows,
                staffTrack, words, lowerHeads);
    }

    record Head(float x, float y) {}

    private static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staffTrack, List<PlayingTechniqueDetector.Word> words) {
        return clear(ink, width, height, gap, headsX, headsY, shafts, sourceRows, staffTrack, words, null);
    }

    private static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staffTrack, List<PlayingTechniqueDetector.Word> words, List<Head> lowerHeads) {
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
        boolean[] verifiedStaff = trackedStaffMask(ink, width, height, left, right, start, end, staffTrack, gap);
        var window = new Window(ink, width, left, start, right - left + 1, end - start + 1, verifiedStaff);
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
            if (lowerHeads != null && !markBand && minY >= bottomHead + 2.5f * gap
                    && memberOwnsLowerHead(queue, size, window, minX, maxX, minY, maxY, lowerHeads, gap))
                continue;
            int inkWidth = maxX - minX + 1, inkHeight = maxY - minY + 1;
            if (lowerHeads != null && !markBand && minY >= bottomHead + 1.8f * gap
                    && inkWidth <= .60f * gap && inkHeight <= .60f * gap
                    && size >= .60f * inkWidth * inkHeight) {
                float dotY = (minY + maxY) * .5f;
                if (lowerHeads.stream().filter(head -> Math.abs(head.x() - center) <= .27f * gap
                        && head.y() - dotY >= 1.15f * gap && head.y() - dotY <= 1.9f * gap).count() == 1)
                    continue;
            }
            if (markBand && ownedReturningCurve(queue, size, window, minX, maxX, shafts, travel, gap,
                    lowerHeads != null ? headsX : null)) continue;
            if (travel > 0 && markBand && ownedBowCaption(
                    ink,width,height,gap,minX,minY,maxX,maxY,words)) continue;
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

    private static boolean memberOwnsLowerHead(int[] pixels, int size, Window window, int left, int right,
            int top, int bottom, List<Head> lowerHeads, float gap) {
        int radius = Math.round(gap * .17f);
        int area = (2 * radius + 1) * (2 * radius + 1);
        for (var head : lowerHeads) {
            int x = Math.round(head.x()), y = Math.round(head.y());
            if (x - radius < left || x + radius > right || y - radius < top || y + radius > bottom) continue;
            int core = 0;
            for (int i = 0; i < size; i++) {
                int xx = pixels[i] % window.width + window.left;
                int yy = pixels[i] / window.width + window.top;
                if (Math.abs(xx - x) <= radius && Math.abs(yy - y) <= radius) core++;
            }
            if (core >= .8f * area) return true;
        }
        return false;
    }

    private static boolean ownedBowCaption(
            byte[] raw, int width, int height, float gap, int minX, int minY, int maxX, int maxY,
            List<PlayingTechniqueDetector.Word> words) {
        for (var word : words) {
            if (!ExpressiveDirectionText.isBowCaption(word.text())) continue;
            int l = Math.round(word.left() * width), r = Math.round(word.right() * width);
            int t = Math.round(word.top() * height), b = Math.round(word.bottom() * height);
            if (l < 0 || r >= width || t < 0 || b >= height
                    || r-l < gap * 3.2f || r-l > gap * 9 || b-t < gap || b-t > gap * 3
                    || minX <= l || maxX >= r || minY <= t || maxY >= b) continue;
            var source = RawNumeralComponents.capture(raw,width,height,l,t,r,b,() -> {},() -> {});
            int letters = 0;
            int left = width, right = -1, top = height, bottom = -1;
            boolean numeral = false;
            for (var glyph : source.components()) {
                if (glyph.size() < gap * gap * .08f) continue;
                letters++;
                if (glyph.left <= maxX && glyph.right >= minX && glyph.top <= maxY && glyph.bottom >= minY) {
                    left = Math.min(left, glyph.left);
                    right = Math.max(right, glyph.right);
                    top = Math.min(top, glyph.top);
                    bottom = Math.max(bottom, glyph.bottom);
                }
                if (glyph.bottom-glyph.top < gap * .65f || glyph.right-glyph.left > gap * 1.6f)
                    continue;
                int[] pixels = java.util.stream.IntStream.range(0,glyph.size()).map(glyph::pixel).toArray();
                if (TupletNumeralBeamBand.detachedThree(
                        pixels,width,glyph.left,glyph.top,glyph.right,glyph.bottom,gap)
                        || TripletRhythmDetector.isIsolatedThree(glyph,width)) numeral = true;
            }
            // Thin antialiasing may join two letters. Keep their bounded shared
            // ink envelope only after ruling out any separate numeral.
            boolean owns = left <= minX + 1 && right >= maxX - 1 && top <= minY + 1 && bottom >= maxY - 1;
            if (owns && letters >= 4 && !numeral) return true;
        }
        return false;
    }

    private static boolean ownedReturningCurve(int[] pixels, int size, Window window, int left, int right,
            List<PrintedBeamShafts.Shaft> shafts, int travel, float gap, float[] headsX) {
        if (right - left < gap * 2.1f || right - left > gap * 3f) return false;
        boolean owned = false;
        for (int i = 0; i < 2; i++) {
            var a = shafts.get(i);
            var b = shafts.get(i + 1);
            if (Math.abs(left - (a.left() + a.right()) * .5f) <= gap * .38f
                    && Math.abs(right - (b.left() + b.right()) * .5f) <= gap * .38f) owned = true;
            if (headsX != null && travel < 0 && left > headsX[i] && right < headsX[i + 1]
                    && left - headsX[i] <= gap * .62f && headsX[i + 1] - right <= gap * .73f) owned = true;
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

    private static boolean[] trackedStaffMask(byte[] raw, int w, int h, int left, int right,
            int top, int bottom, StaffPitchTrack staff, float gap) {
        if (staff == null) return null;
        int step = Math.max(1, Math.round(gap * .22f));
        int band = Math.max(1, Math.round(gap * .19f));
        int bridge = Math.round(gap * 2f);
        int span = right - left + 1;
        boolean[] mask = new boolean[span * (bottom - top + 1)];
        for (int x = left; x <= right; x++) {
            if (!staff.verifiedAt(x)) continue;
            var frame = staff.at(x);
            for (int r = 0; r < 5; r++) {
                int y = Math.round(frame[0] - r * frame[1]);
                if (y - band < top || y + band > bottom) continue;
                // Only an independently tracked rule continuous on both sides may hide dark ink.
                for (int delta = -band; delta <= band; delta++) {
                    if ((raw[(y + delta) * w + x] & 255) < 205
                            && continuousStaffRule(raw, w, h, x, step, bridge, staff, r, delta))
                        mask[(y + delta - top) * span + x - left] = true;
                }
            }
        }
        return mask;
    }

    private static boolean continuousStaffRule(byte[] raw, int w, int h, int x, int step, int bridge,
            StaffPitchTrack staff, int rule, int delta) {
        for (int direction : new int[]{-1,1}) {
            int count = 0, dark = 0;
            for (int d = step; d <= bridge; d += step) {
                int col = x + d * direction;
                if (col < 0 || col >= w || !staff.verifiedAt(col)) return false;
                var frame = staff.at(col);
                int y = Math.round(frame[0] - rule * frame[1]) + delta;
                if (y < 1 || y + 1 >= h) return false;
                count++;
                for (int yy = y - 1; yy <= y + 1; yy++) {
                    if ((raw[yy * w + col] & 255) >= 205) continue;
                    dark++;
                    break;
                }
            }
            if (dark < count * .88f) return false;
        }
        return true;
    }

    private record Window(byte[] plane, int stride, int left, int top, int width, int height, boolean[] trackedMask) {
        private boolean dark(int x, int y, boolean[] staff) {
            return !staff[y - top]
                    && (trackedMask == null || !trackedMask[(y - top) * width + x - left])
                    && (plane[y * stride + x] & 255) < 205;
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
            return bracket(staff, start, end, headTop, headBottom, upper, lower, gap, sourceRows, false);
        }

        private boolean bracket(boolean[] staff, float start, float end, float headTop,
                float headBottom, float upper, float lower, float gap, Rows sourceRows,
                boolean verifyContinuingStroke) {
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
                            && arm(staff, y, middle, slope * .06f, c, d)
                            && (!verifyContinuingStroke || !continuingStroke(
                                    staff, start, end, gap, y, middle, slope * .06f, a, b, c, d)))
                        return true;
            }
            return false;
        }

        /** Require source ownership of both arm traces by one continuing stroke. */
        private boolean continuingStroke(
                boolean[] staff, float start, float end, float gap,
                int y, float middle, float slope, int a, int b, int c, int d) {
            int x0 = Math.max(0, Math.round(start - gap * 2.4f));
            int x1 = Math.min(stride - 1, Math.round(end + gap * 2.4f));
            int sheetHeight = plane.length / stride;
            int y0 = Math.max(0, Math.round(y - gap * 1.65f));
            int y1 = Math.min(sheetHeight - 1, Math.round(y + gap * 1.65f));
            if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > 16_384) return false;
            int expectedLeft = 0, expectedRight = 0;
            for (int x = a; x <= b; x++)
                if (dark(x, y + Math.round(slope * (x - middle)), staff)) expectedLeft++;
            for (int x = c; x <= d; x++)
                if (dark(x, y + Math.round(slope * (x - middle)), staff)) expectedRight++;

            var components = RawNumeralComponents.capture(plane, stride, sheetHeight,
                    x0, y0, x1, y1, 204, () -> {}, () -> {}).components();
            for (var ink : components) {
                boolean extendsLeft = start - ink.left >= gap * 1.25f;
                boolean extendsRight = ink.right - end >= gap * 1.25f;
                if (ink.left > a || ink.right < d || !extendsLeft && !extendsRight)
                    continue;
                int[] high = new int[ink.right - ink.left + 1], low = new int[high.length];
                Arrays.fill(high, sheetHeight);
                int leftInk = 0, rightInk = 0;
                for (int i = 0; i < ink.size(); i++) {
                    int pixel = ink.pixel(i), x = pixel % stride, row = pixel / stride;
                    int column = x - ink.left;
                    high[column] = Math.min(high[column], row);
                    low[column] = Math.max(low[column], row);
                    int modeled = y + Math.round(slope * (x - middle));
                    if (row != modeled) continue;
                    if (x >= a && x <= b && dark(x, row, staff)) leftInk++;
                    if (x >= c && x <= d && dark(x, row, staff)) rightInk++;
                }
                if (leftInk != expectedLeft || rightInk != expectedRight) continue;
                boolean thin = true;
                for (int i = 0; i < high.length; i++)
                    if (low[i] - high[i] + 1 > gap * .43f) {
                        thin = false;
                        break;
                    }
                if ((thin || extendsLeft && extendsRight)
                        && !hasBracketTick(staff, start, end, gap, y, middle, slope, a, d)) return true;
            }
            return false;
        }

        private boolean hasBracketTick(
                boolean[] staff, float start, float end, float gap,
                int y, float middle, float slope, int left, int right) {
            int reach = Math.round(gap * .77f), minimum = Math.round(gap * .49f);
            for (int x = left; x <= right; x++) {
                if (Math.abs(x - start) > gap * .45f && Math.abs(x - end) > gap * .45f) continue;
                int center = y + Math.round(slope * (x - middle));
                if (!dark(x, center, staff)) continue;
                int first = center, last = center;
                while (first > Math.max(top, center - reach) && dark(x, first - 1, staff)) first--;
                while (last < Math.min(top + height - 1, center + reach)
                        && dark(x, last + 1, staff)) last++;
                if (last - first + 1 >= minimum) return true;
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
