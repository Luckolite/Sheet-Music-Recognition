// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** A flat's shaft can borrow beam and staff ink to resemble a divider. */
final class BeamedFlatBarline {
    private BeamedFlatBarline() {}

    static List<MeasureRegion> join(
            List<MeasureRegion> measures, byte[] labels, byte[] gray, int width, int height) {
        if (gray == null
                || labels == null
                || width < 1
                || height < 1
                || gray.length != (long) width * height
                || labels.length != gray.length) return measures;
        List<MeasureRegion> result = null;
        for (int i = 0; i < measures.size(); i++) {
            var left = measures.get(i);
            if (i + 1 < measures.size()
                    && joined(labels, gray, width, height, left, measures.get(i + 1))) {
                var right = measures.get(i + 1);
                if (result == null) result = new ArrayList<>(measures.subList(0, i));
                result.add(
                        new MeasureRegion(
                                left.left(),
                                right.right(),
                                Math.min(left.top(), right.top()),
                                Math.max(left.bottom(), right.bottom())));
                i++;
            } else if (result != null) result.add(left);
        }
        return result == null ? measures : List.copyOf(result);
    }

    private static boolean joined(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            MeasureRegion left,
            MeasureRegion right) {
        float gap = (left.bottom() - left.top()) * height / 8.2f;
        float seam = (right.left() - left.right()) * width;
        if (left.cadenceQuarterBeats() != 0
                || right.cadenceQuarterBeats() != 0
                || gap < 8
                || gap > 32
                || seam <= 0
                || seam > gap * 1.40f
                || Math.abs(left.top() - right.top()) * height > gap * .35f
                || Math.abs(left.bottom() - right.bottom()) * height > gap * .35f) return false;
        int column = Math.round((left.right() + right.left()) * width * .5f);
        float hint = left.bottom() * height - gap * 2.1f;
        if (!hasAccidentalSpine(
                labels, width, height, column, Math.round(hint - gap * 4), Math.round(hint), gap))
            return false;
        float[] rules =
                PrintedDirectionStaff.printedRules(
                        gray, width, height, column + gap * 2.05f, hint, gap);
        if (rules == null)
            rules =
                    PrintedDirectionStaff.printedRules(
                            gray, width, height, column - gap * 2.05f, hint, gap);
        if (rules != null
                && owns(
                        labels,
                        gray,
                        width,
                        height,
                        column,
                        Math.round(rules[0] - 4 * rules[1]),
                        Math.round(rules[0]),
                        rules[1])) return true;
        // Ledger-supported chords can cover the nearest five-rule windows.
        if (rules == null) {
            float sample = column + gap * 6f;
            if (sample < right.right() * width - gap)
                rules = PrintedDirectionStaff.printedRules(gray, width, height, sample, hint, gap);
        }
        return rules != null
                && ownsDownstemPair(
                        labels,
                        gray,
                        width,
                        height,
                        column,
                        Math.round(rules[0] - 4 * rules[1]),
                        Math.round(rules[0]),
                        rules[1]);
    }

    private static boolean hasAccidentalSpine(
            byte[] labels, int width, int height, int column, int top, int bottom, float gap) {
        if (column < 0 || column >= width || top < 0 || bottom >= height) return false;
        int marked = 0;
        for (int y = top; y <= bottom; y++)
            if (labels[y * width + column] == OmrMeasurePostProcessor.CLEF_OR_KEY) marked++;
        return marked >= gap * .85f;
    }

    static boolean owns(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int column,
            int topRule,
            int bottomRule,
            float gap) {
        int reach = Math.round(gap * 4.2f);
        int top = topRule - Math.round(gap), bottom = bottomRule + Math.round(gap * 1.2f);
        if (gap < 8
                || gap > 32
                || column - reach < 0
                || column + reach >= width
                || top < 0
                || bottom >= height) return false;
        if (!hasAccidentalSpine(labels, width, height, column, topRule, bottomRule, gap))
            return false;
        if (solidDivider(gray, width, column, topRule, gap)) return false;
        var leftHead = nearestDyad(labels, width, column, top, bottom, gap, -1);
        var rightHead = nearestDyad(labels, width, column, top, bottom, gap, 1);
        if (leftHead == null || rightHead == null) return false;
        var left =
                PairedTremoloInk.stem(gray, width, height, leftHead.x(), leftHead.lower(), gap, 1);
        var right =
                PairedTremoloInk.stem(
                        gray, width, height, rightHead.x(), rightHead.lower(), gap, 1);
        return left != null
                && right != null
                && continuesAcross(gray, width, height, column, left, right, gap)
                && flatAt(gray, width, height, column, rightHead.upper(), gap);
    }

    private static boolean ownsDownstemPair(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int column,
            int topRule,
            int bottomRule,
            float gap) {
        int top = topRule - Math.round(gap * 4), bottom = bottomRule + Math.round(gap * .6f);
        int reach = Math.round(gap * 4.2f);
        if (column - reach < 0
                || column + reach >= width
                || top < 0
                || bottom >= height
                || !hasAccidentalSpine(labels, width, height, column, topRule, bottomRule, gap)
                || solidDivider(gray, width, column, topRule, gap)) return false;
        var prior = nearestDyad(labels, width, column, top, bottom, gap, -1, 3.85f);
        var next = nearestDyad(labels, width, column, top, bottom, gap, 1, 3.85f);
        if (prior == null || next == null) return false;
        var left = sharedDownstem(gray, width, height, prior, gap);
        var right = sharedDownstem(gray, width, height, next, gap);
        return left != null
                && right != null
                && continuesAcross(gray, width, height, column, left, right, gap, -1);
    }

    private static PairedTremoloInk.Stem sharedDownstem(
            byte[] gray, int width, int height, Dyad chord, float gap) {
        var lower = PairedTremoloInk.stem(gray, width, height, chord.x(), chord.lower(), gap, -1);
        var upper = PairedTremoloInk.stem(gray, width, height, chord.x(), chord.upper(), gap, -1);
        return lower != null
                        && upper != null
                        && Math.abs(lower.x() - upper.x()) <= gap * .15f
                        && Math.abs(lower.endY() - upper.endY()) <= gap * .14f
                ? lower
                : null;
    }

    private static boolean solidDivider(byte[] gray, int width, int column, int top, float gap) {
        int radius = Math.max(1, Math.round(gap * .18f));
        for (int x = column - radius; x <= column + radius; x++) {
            int supported = 0;
            for (int space = 0; space < 4; space++) {
                int first = Math.round(top + (space + .2f) * gap);
                int last = Math.round(top + (space + .8f) * gap);
                int ink = 0;
                for (int y = first; y <= last; y++) if ((gray[y * width + x] & 255) < 155) ink++;
                if (ink >= (last - first + 1) * .90f) supported++;
            }
            if (supported == 4) return true;
        }
        return false;
    }

    private record Dyad(float x, float upper, float lower) {}

    private static Dyad nearestDyad(
            byte[] labels, int width, int column, int top, int bottom, float gap, int side) {
        return nearestDyad(labels, width, column, top, bottom, gap, side, 3.25f);
    }

    private static Dyad nearestDyad(
            byte[] labels,
            int width,
            int column,
            int top,
            int bottom,
            float gap,
            int side,
            float maximumSpan) {
        int first = -1, last = -1, upper = bottom, lower = top;
        for (int distance = Math.round(gap * .65f);
                distance <= Math.round(gap * 4.2f);
                distance++) {
            int x = column + side * distance, count = 0, y0 = bottom, y1 = top;
            for (int y = top; y <= bottom; y++)
                if (labels[y * width + x] == OmrMeasurePostProcessor.NOTEHEAD) {
                    count++;
                    y0 = Math.min(y0, y);
                    y1 = Math.max(y1, y);
                }
            if (count < gap * .62f) {
                if (first >= 0) break;
                continue;
            }
            if (first < 0) first = x;
            last = x;
            upper = Math.min(upper, y0);
            lower = Math.max(lower, y1);
        }
        int span = Math.abs(last - first) + 1;
        int vertical = lower - upper + 1;
        if (span < gap * .54f
                || span > gap * 1.65f
                || vertical < gap * 1.65f
                || vertical > gap * maximumSpan) return null;
        return new Dyad((first + last) * .5f, upper + gap * .55f, lower - gap * .55f);
    }

    private static boolean continuesAcross(
            byte[] gray,
            int width,
            int height,
            int column,
            PairedTremoloInk.Stem left,
            PairedTremoloInk.Stem right,
            float gap) {
        return continuesAcross(gray, width, height, column, left, right, gap, 1);
    }

    private static boolean continuesAcross(
            byte[] gray,
            int width,
            int height,
            int column,
            PairedTremoloInk.Stem left,
            PairedTremoloInk.Stem right,
            float gap,
            int direction) {
        int inset = Math.round(gap * .28f);
        int start = left.x() + inset, end = right.x() - inset;
        if (column - start < gap * .52f || end - column < gap * .52f) return false;
        float slope = (right.endY() - left.endY()) / (float) (right.x() - left.x());
        if (Math.abs(slope) > .26f) return false;
        int radius = Math.max(1, Math.round(gap * .13f)), samples = end - start + 1;
        for (int primary = Math.round(gap * .19f); primary <= Math.round(gap * .51f); primary++)
            for (int spacing = Math.round(gap * .53f);
                    spacing <= Math.round(gap * .96f);
                    spacing++) {
                int first = 0, second = 0, seam = 0;
                for (int x = start; x <= end; x++) {
                    int axis =
                            Math.round(left.endY() + (x - left.x()) * slope) + direction * primary;
                    int companion = axis + direction * spacing;
                    if (Math.min(axis, companion) - radius < 0
                            || Math.max(axis, companion) + radius >= height) return false;
                    boolean outer = rail(gray, width, x, axis, radius);
                    boolean inner = rail(gray, width, x, companion, radius);
                    if (x == column && (!outer || !inner)) break;
                    if (outer) first++;
                    if (inner) second++;
                    if ((gray[(axis + direction * (spacing / 2)) * width + x] & 255) >= 185) seam++;
                }
                if (first >= samples * .94f && second >= samples * .94f && seam >= samples * .28f)
                    return true;
            }
        return false;
    }

    static boolean closedDownstemPair(
            byte[] gray, int width, int height, int[] first, int[] last, float gap) {
        if (first[2] != 1 || last[2] != 1 || last[0] <= first[0]) return false;
        var left = new PairedTremoloInk.Stem(first[0], 0, first[1]);
        var right = new PairedTremoloInk.Stem(last[0], 0, last[1]);
        return continuesAcross(gray, width, height, (first[0] + last[0]) / 2, left, right, gap, -1);
    }

    private static boolean rail(byte[] gray, int width, int x, int y, int radius) {
        for (int dy = -radius; dy <= radius; dy++)
            if ((gray[(y + dy) * width + x] & 255) >= 165) return false;
        return true;
    }

    private static boolean flatAt(
            byte[] gray, int width, int height, int column, float headY, float gap) {
        for (int left = column - Math.round(gap * .35f); left <= column; left++)
            for (int right = left + Math.round(gap * .58f); right <= left + gap * 1.2f; right++) {
                if (column > left + (right - left) * .42f) continue;
                for (int top = Math.round(headY - gap * 2.35f); top <= headY - gap * 1.5f; top += 2)
                    for (int bottom = Math.round(headY + gap * .50f);
                            bottom <= headY + gap * .89f;
                            bottom += 2)
                        if (PrintedFlatGlyph.matches(
                                gray, width, height, left, top, right, bottom, gap)) return true;
            }
        return false;
    }
}
