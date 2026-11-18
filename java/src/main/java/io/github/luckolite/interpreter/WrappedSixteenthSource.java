// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Proves two closed groups of four shared, twice-beamed dyad shafts. */
final class WrappedSixteenthSource {
    record Slot(float x, float upper, float lower) {}
    private record Stem(int x, int tip) {}
    private record Beam(Stem first, Stem last, int direction, int outer, int separation) {
        float y(int x, float depth) {
            return first.tip + (last.tip - first.tip) * (x - first.x) / (float) (last.x - first.x)
                    - direction * depth;
        }
    }

    private WrappedSixteenthSource() {}

    static List<Slot> half(byte[] labels, byte[] gray, int width, int height,
            MeasureRegion region, float gap) {
        var slots = slots(labels, gray, width, height, region, gap);
        if (slots.size() != 8) return List.of();
        var first = group(gray, width, height, slots.subList(0, 4), gap);
        var last = group(gray, width, height, slots.subList(4, 8), gap);
        if (first == null || last == null || first.direction != last.direction) return List.of();
        int pad = Math.round(gap * .27f);
        int from = first.last.x + pad, to = last.first.x - pad;
        if (to - from < gap * .5f) return List.of();
        return leaked(gray, width, height, first, from, to, gap) * 4 < to - from
                && leaked(gray, width, height, last, from, to, gap) * 4 < to - from
                ? slots : List.of();
    }

    private static List<Slot> slots(byte[] labels, byte[] gray, int width, int height,
            MeasureRegion r, float gap) {
        int left = Math.max(0, Math.round(r.left() * width));
        int right = Math.min(width, Math.round(r.right() * width));
        int top = Math.max(0, Math.round(r.top() * height - 4 * gap));
        int bottom = Math.min(height, Math.round(r.bottom() * height - .6f * gap));
        if (gap < 10 || gap > 24 || right - left < 10 * gap || right - left > 22 * gap
                || bottom - top < 10 * gap || bottom - top > 14 * gap) return List.of();
        int[] counts = new int[right - left];
        for (int y = top; y < bottom; y++) {
            int at = y * width + left;
            for (int dx = 0; dx < counts.length; dx++)
                if (headInk(labels, gray, at + dx)) counts[dx]++;
        }
        int threshold = Math.round(1.60f * gap);
        var slots = new ArrayList<Slot>(8);
        for (int x = 0; x < counts.length; ) {
            if (counts[x] < threshold) {
                x++;
                continue;
            }
            int first = x, total = 0, weighted = 0;
            for (; x < counts.length && counts[x] >= threshold; x++) {
                total += counts[x];
                weighted += x * counts[x];
            }
            if (x - first < gap * .35f) continue;
            if (x - first > gap * 1.45f || slots.size() == 8) return List.of();
            float center = left + weighted / (float) total;
            if (!slots.isEmpty()) {
                float spacing = center - slots.get(slots.size() - 1).x;
                if (spacing < gap * 1.28f || spacing > gap * 3.40f) return List.of();
            }
            var slot = heads(labels, gray, width, center, top, bottom, gap);
            if (slot == null) return List.of();
            slots.add(slot);
        }
        return slots;
    }

    private static Slot heads(byte[] labels, byte[] gray, int width, float center,
            int top, int bottom, float gap) {
        int near = Math.round(gap * .3f), x = (int) center;
        if (x < near || x + near >= width) return null;
        int[] rows = new int[bottom - top];
        for (int y = 0; y < rows.length; y++) {
            int offset = (top + y) * width + x - near;
            for (int i = 0; i < 2 * near + 1; i++)
                if (headInk(labels, gray, offset + i)) rows[y]++;
        }
        int threshold = Math.round((2 * near + 1) * .45f);
        float[] centers = new float[2];
        int found = 0;
        for (int y = 0; y < rows.length; ) {
            if (rows[y] < threshold) {
                y++;
                continue;
            }
            int first = y, total = 0, weighted = 0;
            for (; y < rows.length && rows[y] >= threshold; y++) {
                total += rows[y];
                weighted += y * rows[y];
            }
            if (y - first < gap * .3f) continue;
            if (found == 0 && y - first >= gap * 1.5f && y - first <= gap * 2.5f) {
                centers[found++] = top + first + gap * .46f;
                centers[found++] = top + y - 1 - gap * .46f;
            } else if (found < 2 && y - first <= gap * 1.38f) {
                centers[found++] = top + weighted / (float) total;
            } else return null;
        }
        float height = centers[1] - centers[0];
        return found == 2 && height >= gap * .7f && height <= gap * 3.1f
                ? new Slot(center, centers[0], centers[1]) : null;
    }

    private static boolean headInk(byte[] labels, byte[] gray, int at) {
        return labels[at] == OmrMeasurePostProcessor.NOTEHEAD && (gray[at] & 255) < 168;
    }

    static boolean provesGroup(
            byte[] gray, int width, int height, ClosedFourDyadBeam.Head[] heads, float gap) {
        var slots = new ArrayList<Slot>(heads.length);
        for (var head : heads) slots.add(new Slot(head.x(), head.top(), head.bottom()));
        return group(gray, width, height, slots, gap) != null;
    }

    private static Beam group(byte[] gray, int width, int height, List<Slot> slots, float gap) {
        Beam found = null;
        var budget = new PrintedBeamShafts.Budget(100_000);
        for (int direction : new int[] {-1, 1}) {
            var first = endpoint(gray, width, height, slots.get(0), gap, direction, budget);
            var last = endpoint(gray, width, height, slots.get(3), gap, direction, budget);
            if (first == null || last == null || last.x <= first.x
                    || Math.abs(last.tip - first.tip) > (last.x - first.x) * .38f) continue;
            int[] stems = new int[4];
            stems[0] = first.x;
            stems[3] = last.x;
            boolean linked = true;
            for (int i = 1; i < 3; i++) {
                var slot = slots.get(i);
                float t = (slot.x - slots.get(0).x) / (slots.get(3).x - slots.get(0).x);
                float inset = (1 - t) * (first.x - slots.get(0).x) + t * (last.x - slots.get(3).x);
                float tip = first.tip + t * (last.tip - first.tip);
                stems[i] = shaft(gray, width, height, slot, inset, tip, gap, direction);
                linked &= stems[i] > stems[i - 1] && stems[i] < stems[3];
            }
            if (!linked) continue;
            var candidate = rails(gray, width, height, first, last, direction, stems, gap);
            if (candidate == null) continue;
            if (found != null) return null;
            found = candidate;
        }
        return found;
    }

    private static Stem endpoint(byte[] gray, int width, int height, Slot slot, float gap,
            int direction, PrintedBeamShafts.Budget budget) {
        var upper = PrintedBeamShafts.at(gray, width, height, slot.x, slot.upper, gap, budget);
        var lower = PrintedBeamShafts.at(gray, width, height, slot.x, slot.lower, gap, budget);
        Stem result = null;
        for (var a : upper) {
            if (a.direction() != direction) continue;
            for (var b : lower) {
                if (b.direction() != direction || Math.abs(a.endY() - b.endY()) > gap * .18f)
                    continue;
                int left = Math.max(a.left(), b.left()), right = Math.min(a.right(), b.right());
                if (right < left) continue;
                if (result != null) return null;
                result = new Stem((left + right) / 2, (a.endY() + b.endY()) / 2);
            }
        }
        return result;
    }

    private static int shaft(byte[] gray, int width, int height, Slot slot, float inset,
            float tip, float gap, int direction) {
        int lead = Math.round((direction > 0 ? slot.upper : slot.lower) + direction * gap * .2f);
        int stop = Math.round(tip - direction * gap * .35f);
        int n = direction * (stop - lead) + 1;
        if (n < gap * 2 || lead < 0 || lead >= height || stop < 0 || stop >= height) return -1;
        int center = Math.round(slot.x + inset), best = -1, most = 0;
        int near = Math.round(gap * .16f);
        for (int x = Math.max(0, center - near); x <= Math.min(width - 1, center + near); x++) {
            int count = 0, broken = 0, longest = 0;
            for (int s = 0; s < n; s++) {
                if ((gray[(lead + s * direction) * width + x] & 255) < 155) {
                    count++;
                    broken = 0;
                } else longest = Math.max(longest, ++broken);
            }
            if (count >= n * .88f && count > most && longest <= gap * .28f) {
                most = count;
                best = x;
            }
        }
        return best;
    }

    private static Beam rails(byte[] gray, int width, int height, Stem first, Stem last,
            int direction, int[] stems, float gap) {
        int pad = Math.round(gap * .27f);
        for (int outer = 1; outer <= Math.round(gap * .46f); outer++) {
            for (int far = Math.round(gap * .47f); far <= Math.round(gap * 1.10f); far++) {
                var beam = new Beam(first, last, direction, outer, far);
                int white = 0, total = 0;
                boolean valid = true;
                for (int i = 0; i < 3; i++) {
                    int from = stems[i] + pad, to = stems[i+1] - pad;
                    int firstInk = 0, secondInk = 0, clear = 0, count = to - from;
                    for (int x = from; x < to; x++) {
                        if (thick(gray, width, height, x, beam.y(x, outer), gap)) firstInk++;
                        if (thick(gray, width, height, x, beam.y(x, outer + far), gap)) secondInk++;
                        if (!thick(gray, width, height, x, beam.y(x, outer + far * .5f), gap)) clear++;
                    }
                    if (count < gap * .5f || firstInk < count * .86f || secondInk < count * .86f
                            || clear < count * .28f) {
                        valid = false;
                        break;
                    }
                    total += count;
                    white += clear;
                }
                if (valid && white >= total * .64f) return beam;
            }
        }
        return null;
    }

    private static int leaked(byte[] gray, int width, int height, Beam beam,
            int from, int to, float gap) {
        int leak = 0;
        for (int x = from; x < to; x++) {
            if (thick(gray, width, height, x, beam.y(x, beam.outer), gap)
                    && thick(gray, width, height, x, beam.y(x, beam.outer + beam.separation), gap))
                leak++;
        }
        return leak;
    }

    private static boolean thick(byte[] gray, int width, int height, int x, float cy, float gap) {
        int y = Math.round(cy), radius = Math.max(2, Math.round(gap * .125f));
        if (x < 0 || x >= width || y - radius < 0 || y + radius >= height) return false;
        int count = 0;
        for (int row = y - radius; row <= y + radius; row++)
            if ((gray[row * width + x] & 255) < 155) count++;
        return count >= 2 * radius;
    }
}
