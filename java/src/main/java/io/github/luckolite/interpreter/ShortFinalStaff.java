// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Verifies compact playable systems with continuous rules and a printed closing edge. */
final class ShortFinalStaff {
    private ShortFinalStaff() {}

    static boolean proved(byte[] labels, byte[] gray, int width, int height, int[] rows, float gap,
            int left, int right, float slope) {
        if (gray == null || width < 1 || height < 1 || gray.length != (long) width * height
                || rows == null || rows.length != 5 || !Float.isFinite(gap) || gap < 5
                || !Float.isFinite(slope) || Math.abs(slope) > .03f || left < 0 || right >= width
                || right - left < gap * 9 || right - left > gap * 32) return false;
        int probe = Math.max(2, Math.round(gap * .3f));
        // Leave room for the clef at the opening and the terminal bar at the end.
        int start = left + Math.round(gap * 3), end = right - Math.round(gap);
        if (start >= end) return false;
        boolean countedRest = false, roundedRail = false;
        int samples = end - start + 1;
        for (int line = 0; line < 5; line++) {
            if (line > 0 && Math.abs(rows[line] - rows[line - 1] - gap) > gap * .18f) return false;
            int hits = railSupport(gray, width, height, rows[line], start, end, slope, probe, 0);
            if (hits < 0) return false;
            if (hits >= samples * .8f) continue;
            // A semantic peak can land one pixel off parts of the same printed rail.
            // The other four rails and both closing strokes still need their original proof.
            if (!roundedRail && !countedRest && gap >= 8 && hits >= samples * .75f
                    && railSupport(gray, width, height, rows[line], start, end, slope, probe, 1)
                            >= samples * .8f) {
                roundedRail = true;
                continue;
            }
            if (roundedRail || line != 2 || slope != 0f
                    || countedRestCenter(gray, width, height, rows[0], rows[2], gap, left, right) < 0)
                return false;
            countedRest = true;
        }
        int runs = 0;
        boolean previous = false;
        int scanLeft = Math.max(left, right - Math.round(gap));
        int scanRight = Math.min(width - 1, right + Math.round(gap));
        for (int x = scanLeft; x <= scanRight; x++) {
            int shift = Math.round(slope * (x - width / 2f));
            int top = rows[0] + shift, bottom = rows[4] + shift, hits = 0;
            if (top < 0 || bottom >= height) return false;
            for (int y = top; y <= bottom; y++) if ((gray[y * width + x] & 255) <= 190) hits++;
            boolean full = hits >= (bottom - top + 1) * .9f;
            if (full && !previous) runs++;
            previous = full;
        }
        return runs >= 2
                || runs == 1 && !countedRest && !roundedRail
                        && printedMusic(labels, gray, width, height, rows, gap, left, right, slope);
    }

    private static int railSupport(byte[] gray, int width, int height, int row,
            int start, int end, float slope, int probe, int radius) {
        int hits = 0;
        for (int x = start; x <= end; x++) {
            int center = Math.round(row + slope * (x - width / 2f));
            if (center - radius < probe || center + radius >= height - probe) return -1;
            for (int y = center - radius; y <= center + radius; y++) {
                int ink = gray[y * width + x] & 255;
                if (ink <= 190 && (gray[(y - probe) * width + x] & 255) >= ink + 15
                        && (gray[(y + probe) * width + x] & 255) >= ink + 15) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    /** The printed middle rule can disappear optically into a centered counted rest. */
    static int countedRestCenter(
            byte[] gray, int width, int height, int top, int middle, float gap, int left, int right) {
        int from = left + Math.round(gap * 4), to = right - Math.round(gap);
        int offset = Math.max(2, Math.round(gap * .22f));
        if (top < gap * 3 || middle + offset >= height || from >= to) return -1;
        int bestStart = -1, bestEnd = -1;
        for (int x = from; x <= to; x++) {
            if (!restCoreColumn(gray, width, x, middle, offset)) continue;
            int first = x;
            while (x + 1 <= to && restCoreColumn(gray, width, x + 1, middle, offset)) x++;
            if (x - first > bestEnd - bestStart) {
                bestStart = first;
                bestEnd = x;
            }
        }
        int span = bestEnd - bestStart + 1;
        if (bestStart <= from || bestEnd >= to || span < gap * 2.6f
                || span > (right - left) * .58f) return -1;
        int center = (bestStart + bestEnd) / 2;
        if (center < left + (right - left) * .47f) return -1;

        // The short middle stroke still needs its original rule on both sides.
        int rail = Math.round(gap);
        for (int x : new int[] {bestStart - rail, bestEnd + rail})
            if (x < left || x > right || (gray[middle * width + x] & 255) > 190) return -1;

        // A count is detached above the staff and centered over the solid bar.
        int glyphLeft = center - Math.round(gap * 1.2f);
        int glyphRight = center + Math.round(gap * 1.2f);
        int glyphTop = top - Math.round(gap * 3f);
        int glyphBottom = top - Math.max(2, Math.round(gap * .15f));
        int firstX = glyphRight, lastX = glyphLeft, firstY = glyphBottom, lastY = glyphTop, ink = 0;
        for (int y = glyphTop; y <= glyphBottom; y++)
            for (int x = glyphLeft; x <= glyphRight; x++)
                if ((gray[y * width + x] & 255) < 150) {
                    ink++;
                    firstX = Math.min(firstX, x);
                    lastX = Math.max(lastX, x);
                    firstY = Math.min(firstY, y);
                    lastY = Math.max(lastY, y);
                }
        return ink >= gap * gap * .35f && firstX > glyphLeft && lastX < glyphRight
                && firstY > glyphTop && lastY < glyphBottom
                && lastX - firstX >= gap * .50f && lastY - firstY >= gap * .85f
                && Math.abs((firstX + lastX) * .5f - center) < gap * .5f ? center : -1;
    }

    private static boolean restCoreColumn(byte[] gray, int width, int x, int middle, int offset) {
        return (gray[(middle - offset) * width + x] & 255) < 150
                && (gray[middle * width + x] & 255) < 150
                && (gray[(middle + offset) * width + x] & 255) < 150;
    }

    private static boolean printedMusic(byte[] labels, byte[] gray, int width, int height, int[] rows, float gap,
            int left, int right, float slope) {
        if (labels == null || labels.length != (long) width * height) return false;
        int headerEnd = left + Math.round(gap * 3.5f);
        int clefTop = Integer.MAX_VALUE, clefBottom = Integer.MIN_VALUE, clefInk = 0;
        int firstRow = rows[0] - Math.round(gap * 2), lastRow = rows[4] + Math.round(gap * 2);
        int minHeadColumn = Math.max(2, Math.round(gap * .2f));
        int minHeadWidth = Math.max(2, Math.round(gap * .35f));
        int headWidth = 0, headRuns = 0;
        for (int x = left; x < right - Math.round(gap); x++) {
            int shift = Math.round(slope * (x - width / 2f));
            int hits = 0;
            for (int y = Math.max(0, firstRow + shift); y <= Math.min(height - 1, lastRow + shift); y++) {
                int pixel = y * width + x;
                if ((gray[pixel] & 255) > 190) continue;
                byte label = labels[pixel];
                if (x < headerEnd && label == OmrMeasurePostProcessor.CLEF_OR_KEY) {
                    clefTop = Math.min(clefTop, y - shift);
                    clefBottom = Math.max(clefBottom, y - shift);
                    clefInk++;
                }
                if (x >= headerEnd && label == OmrMeasurePostProcessor.NOTEHEAD) hits++;
            }
            if (hits >= minHeadColumn) headWidth++;
            else {
                if (headWidth >= minHeadWidth) headRuns++;
                headWidth = 0;
            }
        }
        if (headWidth >= minHeadWidth) headRuns++;
        return clefInk >= gap * gap && clefBottom - clefTop >= gap * 3.5f && headRuns >= 2;
    }
}
