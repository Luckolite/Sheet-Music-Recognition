// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A pale beam's enclosing ink edges are one band, not two independent beams. */
final class OutlinedBeamInk {
    private OutlinedBeamInk() {}

    private record Band(float middle, int first, int last) {}

    static int count(byte[] gray, int width, int height, int[] stem, float headY, float gap) {
        if (gray == null || stem == null || gap < 6) return 0;
        float stemLength = Math.abs(stem[1] - headY);
        if (stemLength < gap * 2.1f || stemLength > gap * 6) return 0;
        int[] probeOffsets = {Math.round(gap * .35f), Math.round(gap * .55f), Math.round(gap * .7f)};
        int farOffset = Math.round(gap * 1.25f);
        int leftFar = stem[0] - farOffset, rightFar = stem[0] + farOffset;
        if ((leftFar < 1 || leftFar >= width - 1) && (rightFar < 1 || rightFar >= width - 1))
            return 0;
        int bounded = countWindow(gray, width, height, stem, gap, .5f, .85f, probeOffsets, farOffset);
        return bounded > 0
                ? bounded
                : countWindow(gray, width, height, stem, gap, 1.25f, .95f, probeOffsets, farOffset);
    }

    private static int countWindow(
            byte[] gray,
            int width,
            int height,
            int[] stem,
            float gap,
            float outside,
            float maximumThickness,
            int[] probeOffsets,
            int farOffset) {
        int top = Math.max(1, Math.round(stem[1] - (stem[2] < 0 ? outside : 1.9f) * gap));
        int bottom =
                Math.min(height - 2, Math.round(stem[1] + (stem[2] < 0 ? 1.9f : outside) * gap));
        int paper = paper(gray, width, height, stem[0], top, bottom, gap);
        if (paper < 140) return 0;
        int maximum = 0;
        int threshold = Math.min(225, paper - 20), dark = Math.min(165, paper - 60);
        int edge = Math.max(1, Math.round(gap * .2f));
        float tipTolerance = gap * .8f, agreementTolerance = gap * .25f;
        Comparator<Band> bandOrder =
                (a, b) ->
                        Float.compare(
                                (a.middle - stem[1]) * -stem[2],
                                (b.middle - stem[1]) * -stem[2]);
        var first = new ArrayList<Band>(3);
        var bands = new ArrayList<Band>(3);
        for (int side = -1; side <= 1; side += 2) {
            int farX = stem[0] + side * farOffset;
            if (farX < 1 || farX >= width - 1) continue;
            boolean agrees = true;
            int supported = 0;
            for (int probe = 0; probe < probeOffsets.length; probe++) {
                if (supported == 0 && probe == probeOffsets.length - 1) break;
                int x = stem[0] + side * probeOffsets[probe];
                bands.clear();
                if (scanBands(gray, width, x, top, bottom, threshold, dark, edge, gap, maximumThickness, bands, 0)
                        || bands.isEmpty()) continue;
                if (bands.size() > 1) bands.sort(bandOrder);
                Band outer = bands.get(0);
                if (Math.abs(outer.middle - stem[1]) > tipTolerance
                        || !outlined(gray, width, x, outer.first, outer.last, edge, dark)) continue;
                if (supported > 0) {
                    if (first.size() != bands.size()) {
                        agrees = false;
                        break;
                    }
                    for (int i = 0; i < bands.size(); i++)
                        if (Math.abs(first.get(i).middle - bands.get(i).middle) > agreementTolerance
                                || Math.abs(
                                                (first.get(i).last - first.get(i).first)
                                                        - (bands.get(i).last - bands.get(i).first))
                                        > agreementTolerance) {
                            agrees = false;
                            break;
                        }
                    if (!agrees) break;
                } else {
                    var scratch = first;
                    first = bands;
                    bands = scratch;
                }
                supported++;
            }
            if (!agrees || supported < 2 || first.size() <= maximum) continue;
            // The outer beam must continue substantially beyond the short secondary hook.
            if (scanBands(gray, width, farX, top, bottom, threshold, dark, edge, gap,
                    maximumThickness, null, first.get(0).middle)) {
                maximum = first.size();
                if (maximum == 3) return maximum;
            }
        }
        return maximum;
    }

    // Returns true if a near probe has too many bands, or a far probe finds its continuation.
    private static boolean scanBands(
            byte[] gray,
            int width,
            int x,
            int top,
            int bottom,
            int threshold,
            int dark,
            int edge,
            float gap,
            float maximumThickness,
            List<Band> result,
            float expectedMiddle) {
        if (x < 1 || x >= width - 1) return false;
        int start = -1;
        float minimumSize = gap * .35f, maximumSize = gap * maximumThickness;
        float continuationTolerance = gap * .45f;
        for (int y = top, at = top * width + x; y <= bottom + 1; y++, at += width) {
            boolean ink = y <= bottom
                    && (((gray[at - 1] & 255) < threshold ? 1 : 0)
                            + ((gray[at] & 255) < threshold ? 1 : 0)
                            + ((gray[at + 1] & 255) < threshold ? 1 : 0) >= 2);
            if (ink && start < 0) start = y;
            if (!ink && start >= 0) {
                int last = y - 1, size = y - start;
                float middle = (start + last) * .5f;
                float displacement = middle - expectedMiddle;
                if (result == null && displacement >= continuationTolerance) return false;
                if (size >= minimumSize && size <= maximumSize
                        && (result != null || Math.abs(displacement) < continuationTolerance)) {
                    if (result != null) {
                        if (result.size() == 3) return true;
                        result.add(new Band(middle, start, last));
                    } else if (outlined(gray, width, x, start, last, edge, dark)) {
                        return true;
                    }
                }
                start = -1;
            }
        }
        return false;
    }

    private static boolean outlined(
            byte[] gray, int width, int x, int first, int last, int edge, int dark) {
        int centerFirst = first + edge, centerLast = last - edge;
        if (centerFirst > centerLast) return false;
        int leading = 255;
        int centerStart = centerFirst * width + x, centerEnd = centerLast * width + x;
        for (int row = first, pixel = first * width + x; row <= centerFirst; row++, pixel += width)
            leading = Math.min(leading, gray[pixel] & 255);
        if (leading >= dark) return false;
        int trailing = 255;
        for (int row = centerLast, pixel = centerEnd; row <= last; row++, pixel += width)
            trailing = Math.min(trailing, gray[pixel] & 255);
        if (trailing >= dark) return false;
        int center = 0;
        for (int row = centerFirst, pixel = centerStart; row <= centerLast; row++, pixel += width)
            center += gray[pixel] & 255;
        return center > (long) (centerLast - centerFirst + 1) * (Math.max(leading, trailing) + 35);
    }

    private static int paper(
            byte[] gray, int width, int height, int x, int top, int bottom, float gap) {
        int[] hist = new int[256];
        int rowMargin = Math.round(gap), columnMargin = Math.round(gap * 2);
        int firstRow = Math.max(0, top - rowMargin),
                lastRow = Math.min(height - 1, bottom + rowMargin);
        int firstColumn = Math.max(0, x - columnMargin),
                lastColumn = Math.min(width - 1, x + columnMargin);
        for (int y = firstRow; y <= lastRow; y++) {
            int row = y * width;
            for (int at = row + firstColumn, end = row + lastColumn; at <= end; at++) {
                hist[gray[at] & 255]++;
            }
        }
        int total = Math.max(0, lastRow - firstRow + 1) * Math.max(0, lastColumn - firstColumn + 1);
        if (total == 0) return 0;
        int above = total / 4;
        for (int tone = 255; tone > 0; tone--) {
            above -= hist[tone];
            if (above < 0) return tone;
        }
        return 0;
    }
}
