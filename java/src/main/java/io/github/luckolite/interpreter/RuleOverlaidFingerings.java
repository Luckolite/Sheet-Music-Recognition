// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Reads stem-side fingerings with independently witnessed staff lines through their ink. */
final class RuleOverlaidFingerings {
    private RuleOverlaidFingerings() {}

    private record Glyph(
            int digit, int left, int top, int right, int bottom, int shaft, List<Integer> ruleRows) {
        boolean same(Glyph other) {
            if (other == null || digit != other.digit || Math.abs(left - other.left) > 2
                    || Math.abs(top - other.top) > 2 || Math.abs(right - other.right) > 2
                    || Math.abs(bottom - other.bottom) > 2 || Math.abs(shaft - other.shaft) > 2
                    || ruleRows.size() != other.ruleRows.size()) return false;
            for (int i = 0; i < ruleRows.size(); i++)
                if (Math.abs(ruleRows.get(i) - other.ruleRows.get(i)) > 1) return false;
            return true;
        }
    }

    static int digit(
            byte[] raw, byte[] normalized, int width, int height,
            PrintedSlurOwners.Location head, StaffPitchTrack track, float floor, float gap,
            long[] remaining) {
        if (head.y() > floor - gap * 4f
                || !(track.verifiedAt(head.x()) || track.verifiedAt(head.x() - 2f * gap))) return -1;
        var other = find(normalized, width, height, head, floor, gap, remaining, false, false, null);
        if (other == null) return -1;
        var original = find(raw, width, height, head, floor, gap, remaining, false, false, null);
        return other.same(original) ? other.digit() : -1;
    }

    static boolean three(
            byte[] raw, byte[] normalized, int width, int height,
            PrintedSlurOwners.Location head, StaffPitchTrack track, float floor, float gap,
            long[] remaining) {
        if (head.y() > floor - gap * 4f
                || !(track.verifiedAt(head.x()) || track.verifiedAt(head.x() - 2f * gap))) return false;
        var other = find(normalized, width, height, head, floor, gap, remaining, true, false, null);
        return other != null && other.same(find(raw, width, height, head, floor, gap, remaining, true, false, null));
    }

    static int above(byte[] raw, byte[] normalized, int width, int height,
            PrintedSlurOwners.Location head, StaffPitchTrack track, float floor, float gap,
            long[] remaining, List<float[]> rawTwos, List<float[]> normalizedTwos) {
        if (head.y() < floor
                || !(track.verifiedAt(head.x()) || track.verifiedAt(head.x() - 2f * gap))) return -1;
        float ceiling = floor - 4f * gap;
        var candidate = find(normalized, width, height, head, ceiling, gap, remaining, false, true, normalizedTwos);
        if (candidate == null) return -1;
        var original = find(raw, width, height, head, ceiling, gap, remaining, false, true, rawTwos);
        return candidate.same(original) ? candidate.digit() : -1;
    }

    static int below(byte[] raw, byte[] normalized, int width, int height,
            PrintedSlurOwners.Location head, StaffPitchTrack track, float floor, float gap,
            long[] remaining, List<float[]> rawTwos, List<float[]> normalizedTwos) {
        if (head.y() > floor - gap * 4f
                || !(track.verifiedAt(head.x()) || track.verifiedAt(head.x() - 2f * gap))) return -1;
        var other = find(normalized, width, height, head, floor, gap, remaining, false, false, normalizedTwos);
        if (other != null) return other.same(find(raw, width, height, head, floor, gap, remaining,
                false, false, rawTwos)) ? other.digit() : -1;
        var one = find(normalized, width, height, head, floor, gap, remaining, false, false, normalizedTwos, true);
        if (one == null) return -1;
        var original = find(raw, width, height, head, floor, gap, remaining, false, false, rawTwos, true);
        return one.same(original) ? 1 : -1;
    }

    private static Glyph find(
            byte[] raster, int width, int height, PrintedSlurOwners.Location head,
            float staffEdge, float gap, long[] remaining, boolean threeOnly, boolean above, List<float[]> referenceTwos) {
        return find(raster, width, height, head, staffEdge, gap, remaining, threeOnly, above, referenceTwos, false);
    }

    private static Glyph find(
            byte[] raster, int width, int height, PrintedSlurOwners.Location head,
            float staffEdge, float gap, long[] remaining, boolean threeOnly, boolean above,
            List<float[]> referenceTwos, boolean localOne) {
        boolean lowerMatched = referenceTwos != null && !above;
        var shaft = PairedTremoloInk.stem(raster, width, height, head.x(), head.y(), gap, above ? 1 : -1);
        if (shaft == null) return null;
        int left = Math.round(shaft.x() - gap * .9f);
        int right = Math.round(shaft.x() + gap * .9f);
        int top = Math.round(Math.max(shaft.endY() + gap * .24f, staffEdge - gap * 1.8f));
        int bottom = Math.round(staffEdge + gap * .75f);
        if (above) {
            top = Math.round(staffEdge - gap * .85f);
            bottom = Math.round(Math.min(shaft.endY() - gap * .24f, staffEdge + gap * 1.8f));
        }
        if (localOne) top = Math.max(top, Math.round(staffEdge - gap * .95f));
        int flank = Math.round(gap * .78f);
        if (top >= bottom || left - flank < 0 || right + flank >= width || top < 0 || bottom >= height)
            return null;
        int heightOfWindow = bottom - top + 1;
        remaining[0] -= (long) (right - left + 1 + flank * 2) * heightOfWindow;
        if (remaining[0] < 0) return null;
        var rule = new BitSet();
        float innerRule = staffEdge + (above ? gap : -gap);
        for (int y = top; y <= bottom; y++) {
            if (Math.abs(y - staffEdge) > gap * .21f && (localOne || Math.abs(y - innerRule) > gap * .21f))
                continue;
            int before = darkCount(raster, width, y, left - flank, left - 1);
            int after = darkCount(raster, width, y, right + 1, right + flank);
            if (Math.min(before, after) * 5 >= flank * 4) rule.set(y);
        }
        if (rule.isEmpty()) return null;
        var rows = new Row[heightOfWindow];
        int scanLeft = localOne ? Math.round(shaft.x() - gap * .64f) : left;
        int scanRight = localOne ? Math.round(shaft.x() + gap * .64f) : right;
        for (int y = top; y <= bottom; y++) {
            if (rule.get(y)) continue;
            int low = width, high = -1, count = 0;
            for (int x = scanLeft; x <= scanRight; x++) {
                if ((raster[y * width + x] & 255) >= 165) continue;
                low = Math.min(low, x);
                high = x;
                count++;
            }
            int span = high - low + 1;
            if (low > scanLeft && high < scanRight && count >= 2 && span <= gap * 1.2f && count >= span * .45f)
                rows[y - top] = new Row(low, high, count);
        }
        Glyph result = null;
        for (int y = top; y <= bottom;) {
            Row start = rows[y - top];
            if (start == null) {
                y++;
                continue;
            }
            int begin = y, end = y, low = start.left(), high = start.right(), mass = start.mass();
            var obscured = new ArrayList<Integer>();
            boolean bridgedRuleEdge = false;
            while (++y <= bottom) {
                Row row = rows[y - top];
                if (row == null) {
                    if (rule.get(y)) continue;
                    // A thin edge row can vanish beside a witnessed rule.
                    // Leave that row unmasked so the shape proof still sees the missing ink.
                    if ((referenceTwos != null || threeOnly) && !bridgedRuleEdge
                            && (rule.get(y + 1) || y - end > 1 && rule.get(y - 1))) {
                        bridgedRuleEdge = true;
                        continue;
                    }
                    break;
                }
                for (int z = end + 1; z < y; z++)
                    if (rule.get(z)) obscured.add(z);
                end = y;
                low = Math.min(low, row.left());
                high = Math.max(high, row.right());
                mass += row.mass();
            }
            int h = end - begin + 1, w = high - low + 1;
            float center = (low + high) * .5f;
            if (obscured.size() < 2 || obscured.size() > gap * .32f
                    || referenceTwos != null && obscured.get(obscured.size() - 1) - obscured.get(0) + 1 != obscured.size()
                    || begin == top || end == bottom || w < gap * .38f || w > gap * 1.12f
                    || h < gap * .80f || h > gap * 1.53f || Math.abs(center - shaft.x()) > gap * (above ? .30f : .46f)
                    || mass < w * h * .24f) continue;
            var masked = new BitSet();
            for (int z : obscured) masked.set(z * width + low, z * width + high + 1);
            var reading = JoinedDigitTemplates.inspect(
                    raster, width, height, masked, low, begin, high, end, () -> {});
            if (reading == null) continue;
            int digit = reading.digit();
            if (threeOnly) {
                if (!FingeringThreeInk.proves(raster, width, masked, low, begin, high, end, reading))
                    continue;
                digit = 3;
            }
            if (!above && !lowerMatched && digit == 1
                    && maskedFourVeto(raster, width, low, begin, high, end, obscured, remaining))
                continue;
            if (lowerMatched) {
                if (begin - shaft.endY() > gap * 2.05f) continue;
                if (reading.scores()[1] == reading.best() && reading.best() >= .56f
                        && w <= gap * .79f && oneInk(raster, width, low, begin, high, end, obscured)
                        && (reading.best() - reading.runner() >= .05f
                                || maskedOneContrast(raster, width, low, begin, high, end, obscured, remaining))) {
                    digit = 1;
                } else {
                    if (localOne || digit >= 0 && digit != 2
                            || !matchesMaskedTwos(
                                    raster, width, low, begin, high, end, obscured, referenceTwos, remaining, true))
                        continue;
                    digit = 2;
                }
            }
            if (above && digit < 0 && reading.scores()[2] == reading.best()
                    && matchesMaskedTwos(raster, width, low, begin, high, end, obscured, referenceTwos, remaining, false))
                digit = 2;
            if (digit < 1 || digit > (above ? 2 : 4)) continue;
            if (result != null) return null;
            result = new Glyph(digit, low, begin, high, end, shaft.x(), List.copyOf(obscured));
        }
        return result;
    }

    private static boolean oneInk(byte[] raster, int stride, int left, int top, int right, int bottom,
            List<Integer> rules) {
        int height = bottom - top + 1, width = right - left + 1;
        int coreLeft = left, coreRight = right, narrowest = width, supports = 0;
        for (int y = top + Math.round(height * .43f); y <= top + Math.round(height * .63f); y++) {
            if (rules.contains(y)) continue;
            int first = left, last = right;
            while (first <= right && (raster[y * stride + first] & 255) >= 90) first++;
            while (last >= left && (raster[y * stride + last] & 255) >= 90) last--;
            int span = last - first + 1;
            if (span < width * .24f || span > width * .66f) continue;
            coreLeft = Math.max(coreLeft, first);
            coreRight = Math.min(coreRight, last);
            narrowest = Math.min(narrowest, span);
            supports++;
        }
        if (supports < 2 || coreRight - coreLeft + 1 < width * .20f) return false;
        int feet = 0;
        for (int y = top + Math.round(height * .77f); y <= bottom; y++) {
            if (rules.contains(y)) continue;
            int count = darkCount(raster, stride, y, left, right);
            if (count >= width * .59f && count >= narrowest + 2 && ++feet == 2) return true;
        }
        return false;
    }

    private static boolean matchesMaskedTwos(byte[] raster, int stride, int left, int top, int right, int bottom,
            List<Integer> rules, List<float[]> references, long[] remaining, boolean compareRivals) {
        float[] shape = MatchedDetachedThreeShape.vector(raster, stride, left, top, right, bottom);
        remaining[0] -= (long) shape.length * references.size();
        if (remaining[0] < 0) return false;
        var visible = visibleSamples(top, bottom, rules);
        float observedMass = observedMass(shape, visible);
        int matches = 0;
        float required = .84f;
        float minimumSquared = required * required * observedMass;
        for (var reference : references) {
            float dot = 0, referenceMass = 0;
            for (int i = visible.nextSetBit(0); i >= 0; i = visible.nextSetBit(i + 1)) {
                dot += shape[i] * reference[i];
                referenceMass += reference[i] * reference[i];
            }
            if (dot <= 0 || dot * dot < minimumSquared * referenceMass) continue;
            if (compareRivals) {
                float rival = JoinedDigitTemplates.maxMaskedRival(shape, visible, observedMass, remaining);
                if (remaining[0] < 0) return false;
                required = Math.max(required, rival + .09f);
                minimumSquared = required * required * observedMass;
                compareRivals = false;
                if (dot * dot < minimumSquared * referenceMass) continue;
            }
            if (++matches == 2) return true;
        }
        return false;
    }

    private static boolean maskedOneContrast(byte[] raster, int stride, int left, int top, int right, int bottom,
            List<Integer> rules, long[] remaining) {
        float[] scores = contrastScores(raster, stride, left, top, right, bottom, rules, remaining);
        if (remaining[0] < 0 || scores[1] < .50f) return false;
        for (int digit = 0; digit < scores.length; digit++)
            if (digit != 1 && scores[1] - scores[digit] < .12f) return false;
        return true;
    }

    private static boolean maskedFourVeto(byte[] raster, int stride, int left, int top, int right, int bottom,
            List<Integer> rules, long[] remaining) {
        float[] scores = contrastScores(raster, stride, left, top, right, bottom, rules, remaining);
        // A thick staff rule can obscure a four's crossbar in the binary samples.
        return remaining[0] < 0 || scores[4] >= .55f && scores[4] - scores[1] >= .12f;
    }

    private static float[] contrastScores(byte[] raster, int stride, int left, int top, int right, int bottom,
            List<Integer> rules, long[] remaining) {
        float[] shape = MatchedDetachedThreeShape.vector(raster, stride, left, top, right, bottom);
        var visible = visibleSamples(top, bottom, rules);
        return JoinedDigitTemplates.maskedScores(shape, visible, observedMass(shape, visible), -1, remaining);
    }

    private static BitSet visibleSamples(int top, int bottom, List<Integer> rules) {
        var visible = new BitSet(16 * 24);
        float yScale = (bottom - top + 1) / 24f;
        for (int y = 0; y < 24; y++) {
            float yy = Math.max(top, Math.min(bottom, top + (y + .5f) * yScale - .5f));
            // Bilinear sampling touches both pixel rows. Exclude any sample that touches the rule.
            if (rules.contains((int) yy) || rules.contains((int) Math.ceil(yy))) continue;
            visible.set(y * 16, (y + 1) * 16);
        }
        return visible;
    }

    private static float observedMass(float[] samples, BitSet visible) {
        float mass = 0;
        for (int i = visible.nextSetBit(0); i >= 0; i = visible.nextSetBit(i + 1))
            mass += samples[i] * samples[i];
        return mass;
    }

    private record Row(int left, int right, int mass) {}

    private static int darkCount(byte[] raster, int width, int y, int left, int right) {
        int count = 0;
        for (int x = left; x <= right; x++)
            if ((raster[y * width + x] & 255) < 165) count++;
        return count;
    }
}
