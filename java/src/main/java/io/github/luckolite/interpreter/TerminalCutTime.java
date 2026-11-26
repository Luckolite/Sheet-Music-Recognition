// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;

/** A cut-time sign after the terminal double bar belongs to the next measure. */
final class TerminalCutTime {
    private TerminalCutTime() {}

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        var meter = detect(score, gray, width, height);
        if (meter == null) return score;
        var changes = new ArrayList<>(score.meterChanges());
        changes.add(meter);
        return score.withMeterChanges(changes);
    }

    static ScoreMeterChange detect(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        var bars = score.measures();
        if (bars.isEmpty() || width < 1 || height < 1 || gray == null
                || gray.length != (long) width * height) return null;
        int after = bars.size();
        var bar = bars.get(after - 1);
        if (score.meterChanges().stream().anyMatch(m -> m.measureIndex() >= after)) return null;
        float scale = (bar.bottom() - bar.top()) * height / 8f;
        if (scale < 6 || scale > 80) return null;
        int boundary = Math.round(bar.right() * width);
        int left = Math.max(0, Math.round(boundary - scale * 9));
        int right = Math.min(width, Math.round(boundary + scale * 6));
        int top = Math.max(0, Math.round(bar.top() * height));
        int bottom = Math.min(height, Math.round(bar.bottom() * height));
        int w = right - left, h = bottom - top;
        if (w < 48 || h < 25 || boundary < left || boundary >= right) return null;
        byte[] crop = new byte[w * h];
        for (int y = 0; y < h; y++)
            System.arraycopy(gray, (top + y) * width + left, crop, y * w, w);

        for (var staff : RawStaffLineDetector.detect(crop, w, h)) {
            float gap = staff.gap();
            if (gap < scale * .6f || gap > scale * 1.6f) continue;
            int first = staff.top(), last = staff.bottom();
            int edge = boundary - left;
            var rules = new ArrayList<int[]>();
            boolean prior = false;
            for (int x = Math.max(0, Math.round(edge - gap * 1.55f));
                    x <= Math.min(w - 1, Math.round(edge + gap * .55f)); x++) {
                int present = 0;
                for (int y = first; y <= last; y++)
                    if ((crop[y * w + x] & 255) < 155) present++;
                boolean full = present >= (last - first + 1) * .88f;
                if (full) {
                    if (!prior) rules.add(new int[] {x, x});
                    else rules.get(rules.size() - 1)[1] = x;
                }
                prior = full;
            }
            if (rules.size() != 2) continue;
            int[] a = rules.get(0), b = rules.get(1);
            if (a[1] - a[0] + 1 > gap * .56f || b[1] - b[0] + 1 > gap * .56f
                    || b[0] - a[1] < gap * .17f || b[0] - a[1] > gap * .95f) continue;
            if (score.notes().stream().anyMatch(n -> n.measureIndex() == after - 1
                    && n.pageY() * height >= top + first - gap * .6f
                    && n.pageY() * height <= top + last + gap * .6f
                    && (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * width
                            >= left + a[0] - gap * .25f)) continue;

            int min = w, max = -1;
            int searchRight = Math.min(w - 1, Math.round(b[1] + gap * 3.4f));
            for (int x = b[1] + Math.max(2, Math.round(gap * .20f)); x <= searchRight; x++) {
                int distinct = 0;
                for (int y = first + Math.round(gap * .45f); y <= last - gap * .45f; y++) {
                    boolean onStaff = false;
                    for (int rail : staff.rows()) if (Math.abs(y - rail) <= gap * .16f) onStaff = true;
                    if (!onStaff && (crop[y * w + x] & 255) < 155) distinct++;
                }
                if (distinct >= 2) {
                    min = Math.min(min, x);
                    max = x;
                }
            }
            if (max - min < gap * .85f || max - min > gap * 2.6f
                    || min - b[1] < gap * .22f || min - b[1] > gap * 1.4f) continue;
            boolean terminalRules = true;
            for (int row : new int[] {first, last}) {
                int end = -1;
                int reach = Math.min(w - 1, Math.round(max + gap * 1.5f));
                for (int x = min; x <= reach; x++)
                    if ((crop[row * w + x] & 255) <= 175) end = x;
                if (end < max + gap * .12f || end >= reach - 1) terminalRules = false;
            }
            if (!terminalRules) continue;
            for (int l = min; l <= min + gap * .52f; l++)
                for (int r = Math.max(l + 3, max - Math.round(gap * .22f));
                        r <= Math.min(w - 1, max + gap * .27f); r++)
                    if (CommonTimeMeter.readTerminalCut(crop, w, h, l, r, first, gap))
                        return new ScoreMeterChange(after, 2, 2);
        }
        return null;
    }
}
