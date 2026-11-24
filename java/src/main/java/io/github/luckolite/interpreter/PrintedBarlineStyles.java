// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Retains double and terminal heavy rules directly at physical measure ends. */
final class PrintedBarlineStyles {
    private static final int DARK = 150;

    record Style(int measureIndex, String style) {}

    private record Stroke(int first, int last) {
        int width() {
            return last - first + 1;
        }

        float center() {
            return (first + last) * .5f;
        }
    }

    private PrintedBarlineStyles() {}

    static List<Style> capture(
            byte[] gray, int width, int height, List<MeasureRegion> measures,
            List<Integer> continuations) {
        return capture(gray, null, width, height, measures, continuations);
    }

    static List<Style> capture(
            byte[] gray, byte[] source, int width, int height, List<MeasureRegion> measures,
            List<Integer> continuations) {
        if (gray == null || gray.length != (long) width * height || measures.isEmpty())
            return List.of();
        var staffs = RawStaffLineDetector.detect(gray, width, height);
        var result = new ArrayList<Style>();
        for (int i = 0; i < measures.size(); i++) {
            if (continuations.contains(i + 1)) continue;
            var region = measures.get(i);
            var next = i + 1 < measures.size() ? measures.get(i + 1) : null;
            float right = region.right() * width;
            RawStaffLineDetector.StaffLines staff = null;
            for (var candidate : staffs) {
                if (candidate.top() < region.top() * height
                        || candidate.bottom() > region.bottom() * height) continue;
                if (staff != null) {
                    staff = null;
                    break;
                }
                staff = candidate;
            }
            if (staff == null || right - region.left() * width < staff.gap() * 2) continue;
            float gap = staff.gap();
            boolean interior = next != null
                    && Math.abs(next.top() - region.top()) * height <= gap;
            float boundary = interior ? (right + next.left() * width) * .5f : right;
            if (Math.abs(boundary - right) > gap * .85f) continue;
            // A page-level staff track limits the row. Refine the raw rails beside
            // this barline so slope or skew cannot move its vertical evidence.
            float hint = staff.bottom();
            float[] local = PrintedDirectionStaff.printedRules(
                    gray, width, height, boundary - gap * 2.5f, hint, gap);
            if (local == null)
                local = TerminalDirectionRails.read(
                        gray, width, height, right - gap * 2, hint, gap,
                        region.left(), region.right());
            var style = local == null ? null : styleAt(gray, width, height, boundary, right, interior, local, false);
            float slot = right - region.left() * width;
            if (style == null && source != null && source.length == gray.length && interior
                    && region.cadenceQuarterBeats() == 1 && slot >= gap * 2.75f && slot <= gap * 4.25f
                    && boundary - right >= gap * .35f && boundary - right <= gap * .71f) {
                var rails = DottedDyadMeasureSeparator.localRails(
                        gray, width, height, Math.round(right - .55f * gap), hint, gap);
                var sourceRails = DottedDyadMeasureSeparator.localRails(
                        source, width, height, Math.round(right - .55f * gap), hint, gap);
                if (rails != null && sourceRails != null && Math.abs(rails[0] - sourceRails[0]) <= 1
                        && Math.abs(rails[1] - sourceRails[1]) <= .25f
                        && "light-light".equals(styleAt(gray, width, height, boundary, right, true, rails, true))
                        && "light-light".equals(styleAt(source, width, height, boundary, right, true, sourceRails, true)))
                    style = "light-light";
            }
            if (style == null) continue;
            if (i > 0) {
                var prior = measures.get(i - 1);
                if (Math.abs(prior.top() - region.top()) * height <= gap
                        && Math.abs(prior.right() - region.right()) * width <= gap * .35f) continue;
            }
            result.add(new Style(i, style));
        }
        return List.copyOf(result);
    }

    private static String styleAt(byte[] gray, int width, int height, float boundary,
            float regionRight, boolean interior, float[] rails, boolean compactCadence) {
        float gap = rails[1], bottom = rails[0], top = bottom - 4 * gap;
        int a = Math.round(top), b = Math.round(bottom);
        int left = Math.round(boundary - 1.1f * gap);
        int right = Math.round(boundary + 1.2f * gap);
        if (left < 1 || right >= width - 1 || a < 0 || b >= height) return null;
        var strokes = new ArrayList<Stroke>();
        int first = -1;
        for (int x = left; x <= right; x++) {
            boolean ink = solid(gray, width, x, top, gap);
            if (ink && first < 0) first = x;
            if (first >= 0 && (!ink || x == right)) {
                strokes.add(new Stroke(first, ink ? x : x - 1));
                first = -1;
            }
        }
        if (strokes.size() != 2) return null;
        var thin = strokes.get(0);
        var outer = strokes.get(1);
        float spacing = outer.center() - thin.center();
        float mean = (outer.center() + thin.center()) * .5f;
        float lightWidth = Math.max(3, gap * (compactCadence ? .46f : .28f));
        if (spacing < gap * .27f || spacing > gap * .9f
                || thin.width() > lightWidth
                || thin.first() == left || outer.last() == right
                || Math.abs(mean - boundary) > gap * .66f
                || Math.abs(outer.center() - regionRight) > gap * 1.25f
                || !clearSeam(gray, width, (thin.last() + outer.first()) / 2, top, gap)
                        && (interior || !clearTerminalSeam(gray, width, top, gap, thin, outer)))
            return null;
        if (!OmrMeasurePostProcessor.verticalRuleAt(gray, width, Math.round(thin.center()), a, b, gap)
                || !OmrMeasurePostProcessor.verticalRuleAt(gray, width, Math.round(outer.center()), a, b, gap)
                || ClosedHeadBarlineGuard.attached(gray, width, height, Math.round(thin.center()), a, b, gap)
                || ClosedHeadBarlineGuard.attached(gray, width, height, Math.round(outer.center()), a, b, gap))
            return null;
        // Small thickness differences occur even on equal printed rules. The
        // widened outer stroke must be optically distinct before using light-heavy.
        boolean heavy = outer.width() >= thin.width() + Math.max(3, gap * .19f)
                && outer.width() >= thin.width() * 1.8f && outer.width() >= gap * .40f;
        if (heavy) {
            if (interior || outer.width() > gap * .85f
                    || !endsStaff(gray, width, outer.last(), top, gap)) return null;
            var repeat = PrintedRepeatGlyphs.provedColumns(gray, width, height, top, bottom, gap);
            return repeat.get(thin.first(), outer.last() + 1).isEmpty()
                    ? "light-heavy" : null;
        }
        return Math.abs(outer.width() - thin.width()) <= Math.max(1, gap * (compactCadence ? .18f : .11f))
                        && outer.width() <= lightWidth
                ? "light-light" : null;
    }

    private static boolean solid(byte[] gray, int width, int x, float top, float gap) {
        for (int s = 0; s < 4; s++) {
            int first = Math.round(top + (s + .22f) * gap);
            int last = Math.round(top + (s + .78f) * gap);
            int dark = 0;
            for (int y = first; y <= last; y++)
                if ((gray[y * width + x] & 255) < DARK) dark++;
            if (dark < (last - first + 1) * .78f) return false;
        }
        return true;
    }

    private static boolean clearSeam(byte[] gray, int width, int x, float top, float gap) {
        for (int s = 0; s < 4; s++)
            if ((gray[Math.round(top + (s + .5f) * gap) * width + x] & 255) < 205)
                return false;
        return true;
    }

    /** Terminal stroke fringes can cover the left half of an otherwise open seam. */
    private static boolean clearTerminalSeam(byte[] gray, int width, float top, float gap,
            Stroke inner, Stroke outer) {
        if (!endsStaff(gray, width, outer.last(), top, gap)) return false;
        for (int x = inner.last() + 1; x < outer.first(); x++) {
            if (clearSeam(gray, width, x, top, gap)) return true;
        }
        return false;
    }

    private static boolean endsStaff(byte[] gray, int width, int edge, float top, float gap) {
        int from = edge + Math.round(gap * .55f), to = edge + Math.round(gap * 1.1f);
        if (to >= width) return false;
        int present = 0;
        for (int line = 0; line < 5; line++) {
            int y = Math.round(top + line * gap), dark = 0;
            for (int x = from; x <= to; x++)
                if ((gray[y * width + x] & 255) < DARK) dark++;
            if (dark >= (to - from + 1) * .5f) present++;
        }
        return present <= 1;
    }
}
