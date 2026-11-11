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
        for (int line = 0; line < 5; line++) {
            if (line > 0 && Math.abs(rows[line] - rows[line - 1] - gap) > gap * .18f) return false;
            int hits = 0;
            for (int x = start; x <= end; x++) {
                int y = Math.round(rows[line] + slope * (x - width / 2f));
                if (y < probe || y >= height - probe) return false;
                int ink = gray[y * width + x] & 255;
                if (ink <= 190 && (gray[(y - probe) * width + x] & 255) >= ink + 15
                        && (gray[(y + probe) * width + x] & 255) >= ink + 15) hits++;
            }
            if (hits < (end - start + 1) * .8f) return false;
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
                || runs == 1 && printedMusic(labels, gray, width, height, rows, gap, left, right, slope);
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
