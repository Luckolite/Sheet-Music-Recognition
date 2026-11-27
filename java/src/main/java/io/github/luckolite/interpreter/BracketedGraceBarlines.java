// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Recovers a staff barline merged with a low grace flag below a tuplet bracket. */
final class BracketedGraceBarlines {
    private BracketedGraceBarlines() {}

    static List<MeasureRegion> recover(List<MeasureRegion> measures, byte[] labels, byte[] gray,
            byte[] source, int width, int height) {
        if (labels == null || gray == null || source == null || labels.length != (long) width * height
                || labels.length != gray.length || gray.length != source.length) return measures;
        List<MeasureRegion> result = null;
        for (int i = 0; i < measures.size(); i++) {
            var m = measures.get(i);
            int x = separator(m, labels, gray, source, width, height);
            if (x < 0) {
                if (result != null) result.add(m);
                continue;
            }
            if (result == null) result = new ArrayList<>(measures.subList(0, i));
            float gap = (m.bottom() - m.top()) * height / 8.2f;
            // Keep a grace head tucked left of the barline in the next measure.
            float onset = (x - gap * .55f) / width;
            float previousRight = onset - .0045f;
            result.add(new MeasureRegion(m.left(), previousRight, m.top(), m.bottom()));
            result.add(new MeasureRegion(onset, m.right(), m.top(), m.bottom()));
        }
        return result == null ? measures : List.copyOf(result);
    }

    private static int separator(MeasureRegion region, byte[] labels, byte[] gray, byte[] source,
            int width, int height) {
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        int left = Math.round(region.left() * width), right = Math.round(region.right() * width);
        float expectedBottom = region.bottom() * height - gap * 2.1f;
        int mid = Math.round(expectedBottom - gap * 2);
        if (gap < 10 || gap > 26 || right - left < 45 * gap || region.cadenceQuarterBeats() != 0
                || left < 0 || right >= width || mid - gap * 4 < 0 || mid + gap * 4 >= height)
            return -1;
        int found = -1;
        for (int x = left + Math.round(gap * 7); x < right - gap * 7; x++) {
            if (!dark(gray, width, x, mid) || !dark(gray, width, x, mid - Math.round(gap * .5f))
                    || !dark(gray, width, x, mid + Math.round(gap * .5f))) continue;
            if (!printedSeparator(labels, gray, width, height, x, expectedBottom, gap)
                    || gray != source && !printedSeparator(labels, source, width, height, x, expectedBottom, gap))
                continue;
            if (found >= 0 && x - found > gap * .25f) return -1;
            if (found < 0) found = x;
        }
        return found;
    }

    private static boolean printedSeparator(byte[] labels, byte[] gray, int width, int height,
            int x, float expectedBottom, float gap) {
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, x - 2 * gap, expectedBottom, gap);
        if (rails == null || Math.abs(rails[0] - expectedBottom) > gap * .40f) return false;
        int bottom = Math.round(rails[0]), top = Math.round(rails[0] - 4 * rails[1]);
        gap = rails[1];
        if (dark(gray, width, x, top - Math.round(gap * .25f))
                || !dark(gray, width, x, top) || !dark(gray, width, x, bottom)) return false;
        int shaft = 0, ink = 0;
        for (int y = top; y <= bottom; y++) {
            if (dark(gray, width, x, y)) ink++;
            if (labels[y * width + x] == OmrMeasurePostProcessor.STEM_OR_REST) shaft++;
        }
        if (ink < (bottom - top + 1) * .94f || shaft < (bottom - top + 1) * .60f) return false;
        if (!rightBracketStartsAbove(gray, width, x, top, gap)) return false;
        return hasGraceAndPrincipal(labels, width, x, bottom, gap);
    }

    private static boolean rightBracketStartsAbove(byte[] gray, int width, int x, int top, float gap) {
        for (int h = x + Math.round(gap * .75f); h <= x + gap * 2.15f; h++)
            for (int y = top - Math.round(gap * 1.65f); y <= top - gap * .60f; y++) {
                if (!dark(gray, width, h, y)
                        || dark(gray, width, h - Math.round(gap * .35f), y)) continue;
                int reach = Math.round(gap * 2.5f), support = 0;
                for (int d = 1; d <= reach; d++)
                    if (dark(gray, width, h + d, y)) support++;
                if (support < reach * .90f) continue;
                int hook = Math.round(gap * .60f), hookSupport = 0;
                for (int d = 1; d <= hook; d++)
                    if (dark(gray, width, h, y + d)) hookSupport++;
                if (hookSupport >= hook * .85f) return true;
            }
        return false;
    }

    private static boolean hasGraceAndPrincipal(byte[] labels, int width, int x, int bottom, float gap) {
        int small = 0, full = 0;
        for (int y = bottom + 1; y <= bottom + gap * 1.85f; y++)
            for (int xx = x - Math.round(gap * .65f); xx <= x + gap * .85f; xx++)
                if (labels[y * width + xx] == OmrMeasurePostProcessor.NOTEHEAD) small++;
        for (int y = bottom - Math.round(gap * .6f); y <= bottom + gap * 1.75f; y++)
            for (int xx = x + Math.round(gap * 1.45f); xx <= x + gap * 3.8f; xx++)
                if (labels[y * width + xx] == OmrMeasurePostProcessor.NOTEHEAD) full++;
        return small >= gap * gap * .14f && small <= gap * gap * .70f
                && full >= gap * gap * .63f && full >= small * 1.55f;
    }

    private static boolean dark(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 255) < 155;
    }
}
