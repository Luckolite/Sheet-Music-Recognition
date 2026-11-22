// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Recovers barlines when two fingering rules below a staff obscured the layout. */
final class FingeringGuideBarlines {
    private FingeringGuideBarlines() {}

    static List<MeasureRegion> recover(List<MeasureRegion> measures,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != raw.length
                || gray.length != (long) width * height) return measures;
        List<MeasureRegion> revised = null;
        for (int i = 0; i < measures.size(); i++) {
            var measure = measures.get(i);
            var splits = separators(measure, gray, width, height);
            if (!splits.isEmpty() && gray != raw && !agree(splits, separators(measure, raw, width, height)))
                splits = List.of();
            if (splits.isEmpty()) {
                if (revised != null) revised.add(measure);
                continue;
            }
            if (revised == null) revised = new ArrayList<>(measures.subList(0, i));
            float left = measure.left();
            for (var split : splits) {
                int clearance = Math.round(split.gap * .45f);
                revised.add(new MeasureRegion(left, (split.x - clearance) / width, measure.top(), measure.bottom()));
                left = (split.x + clearance) / width;
            }
            revised.add(new MeasureRegion(left, measure.right(), measure.top(), measure.bottom()));
        }
        return revised == null ? measures : List.copyOf(revised);
    }

    private record Rule(float x, float gap) {}

    private static boolean agree(List<Rule> first, List<Rule> second) {
        return first.size() == second.size()
                && Math.abs(first.get(0).x - second.get(0).x) <= 2
                && Math.abs(first.get(1).x - second.get(1).x) <= 2;
    }

    private static List<Rule> separators(MeasureRegion measure, byte[] gray, int width, int height) {
        int left = Math.round(measure.left() * width), right = Math.round(measure.right() * width);
        int top = Math.round(measure.top() * height), bottom = Math.round(measure.bottom() * height);
        float approximateGap = (bottom - top) / 8.2f;
        if (approximateGap < 10 || approximateGap > 26 || measure.cadenceQuarterBeats() != 0
                || right - left < approximateGap * 72 || left < 0 || right >= width || top < 0 || bottom >= height)
            return List.of();
        var rules = new ArrayList<Rule>(2);
        for (int x = left + Math.round(approximateGap * 25); x <= right - approximateGap * 3; x++) {
            int run = -1;
            for (int y = top; y <= bottom; y++) {
                if ((gray[y * width + x] & 255) < 165) {
                    if (run < 0) run = y;
                    continue;
                }
                if (run >= 0 && y - run >= approximateGap * 3.6f && y - run <= approximateGap * 4.5f) {
                    float gap = (y - run - 2) / 4f;
                    if (isolated(gray, width, height, x, run, gap)) {
                        addRule(rules, x, gap);
                        if (rules.size() > 2) return List.of();
                    }
                }
                run = -1;
            }
        }
        if (rules.size() != 2) return List.of();
        var a = rules.get(0); var b = rules.get(1);
        return a.x - left >= a.gap * 26 && b.x - a.x >= a.gap * 26
                && right - b.x <= b.gap * 9 && Math.abs(a.gap - b.gap) <= 2f ? rules : List.of();
    }

    private static void addRule(List<Rule> rules, int x, float gap) {
        if (rules.isEmpty() || x - rules.get(rules.size() - 1).x > 4) {
            rules.add(new Rule(x, gap));
            return;
        }
        int last = rules.size() - 1;
        var previous = rules.get(last);
        rules.set(last, new Rule((x + previous.x) * .5f, (gap + previous.gap) * .5f));
    }

    private static boolean isolated(byte[] gray, int width, int height, int x, int top, float gap) {
        int reach = Math.round(gap * .72f);
        if (x - reach < 0 || x + reach >= width || top < 2 || top + gap * 6.7f >= height) return false;
        // The two lower rules run across the barline column without a vertical join.
        for (int staffLine = 0; staffLine < 7; staffLine++) {
            float distance = staffLine < 5 ? staffLine : staffLine == 5 ? 5.12f : 6.27f;
            if (!horizontal(gray, width, x, Math.round(top + gap * distance), reach)) return false;
            if (staffLine == 6) break;
            int center = Math.round(top + gap * (staffLine + .53f));
            for (int xx = x - reach; xx <= x + reach; xx++) {
                if (staffLine < 4 && Math.abs(xx - x) < gap * .30f) continue;
                if ((gray[center * width + xx] & 255) < 180) return false;
            }
        }
        return true;
    }

    private static boolean horizontal(byte[] gray, int width, int x, int y, int reach) {
        for (int row = y - 2; row <= y + 2; row++) {
            int ink = 0;
            for (int col = x - reach; col <= x + reach; col++)
                if ((gray[row * width + col] & 255) < 165) ink++;
            if (ink >= reach * 1.7f) return true;
        }
        return false;
    }
}
