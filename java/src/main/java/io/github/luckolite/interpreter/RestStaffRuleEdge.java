// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Extends an already proved staff-rule mask by one locally continuous edge row. */
final class RestStaffRuleEdge {
    private RestStaffRuleEdge() {}

    static boolean[] extend(
            byte[] gray,
            int width,
            int height,
            int top,
            float staffTop,
            float gap,
            boolean[] mask) {
        return extend(gray, width, height, top, staffTop, gap, mask, false, null);
    }

    static boolean[] extendContrasted(
            byte[] gray,
            int width,
            int height,
            int top,
            float staffTop,
            float gap,
            boolean[] mask) {
        return extend(gray, width, height, top, staffTop, gap, mask, true, null);
    }

    static boolean[] extend(
            byte[] gray, int width, int height, int top, float staffTop, float gap,
            boolean[] mask, OrdinaryRuns ordinaryRuns) {
        return extend(gray, width, height, top, staffTop, gap, mask, false, ordinaryRuns);
    }

    private static boolean[] extend(
            byte[] gray,
            int width,
            int height,
            int top,
            float staffTop,
            float gap,
            boolean[] mask,
            boolean contrasted,
            OrdinaryRuns ordinaryRuns) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || mask == null
                || top < 0
                || (long) top + mask.length > height
                || gap < 6
                || !Float.isFinite(gap)
                || !Float.isFinite(staffTop)) return mask;
        boolean[] result = mask;
        int minimum = Math.max(3, Math.round(gap * 3));
        if (minimum > width) return mask;
        int radius = Math.max(3, Math.round(gap * .35f));
        float edge = gap * .35f;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) continue;
            int y = top + i;
            int adjacent =
                    i > 0 && mask[i - 1] ? y - 1 : i + 1 < mask.length && mask[i + 1] ? y + 1 : -1;
            if (adjacent < 0) continue;
            if (contrasted && (y < radius || y + radius >= height)) continue;
            float nearest = staffTop + Math.round((y - staffTop) / gap) * gap;
            if (Math.abs(y - nearest) > edge) continue;
            int row = y * width, neighbor = adjacent * width;
            boolean proven = contrasted
                    ? contrastedRun(gray, width, row, neighbor, radius * width, minimum)
                    : ordinaryRuns != null
                            ? ordinaryRuns.proves(y, adjacent, minimum)
                            : sharedRun(gray, width, row, neighbor, minimum) == minimum;
            if (proven) {
                if (result == mask) result = mask.clone();
                result[i] = true;
            }
        }
        return result;
    }

    private static boolean contrastedRun(
            byte[] gray, int width, int row, int adjacent, int radius, int minimum) {
        int run = 0, above = row - radius, below = row + radius;
        for (int x = 0; x < width; x++) {
            int value = gray[row + x] & 255, other = gray[adjacent + x] & 255;
            if (value < 200 && other < 200
                    && Math.min(gray[above + x] & 255, gray[below + x] & 255)
                            - Math.max(value, other) >= 35) {
                if (++run == minimum) return true;
            } else run = 0;
        }
        return false;
    }

    /** Returns the requested run length or the exact shorter maximum after a complete search. */
    private static int sharedRun(byte[] gray, int width, int row, int adjacent, int minimum) {
        int run = 0, longest = 0;
        for (int x = 0; x < width; x++) {
            if ((gray[row + x] & 255) < 170 && (gray[adjacent + x] & 255) < 170) {
                if (++run == minimum) return minimum;
            } else {
                longest = Math.max(longest, run);
                run = 0;
            }
        }
        return Math.max(longest, run);
    }

    /** Shares ordinary edge proof across placements and across either direction of one row pair. */
    static final class OrdinaryRuns {
        private final byte[] gray;
        private final int width;
        private final int[] rowLongest, bounds;

        OrdinaryRuns(byte[] gray, int width, int[] rowLongest) {
            this.gray = gray;
            this.width = width;
            this.rowLongest = rowLongest;
            bounds = new int[rowLongest.length];
        }

        private boolean proves(int row, int adjacent, int minimum) {
            if (rowLongest[row] < minimum || rowLongest[adjacent] < minimum) return false;
            int first = Math.min(row, adjacent), bound = bounds[first];
            if (bound < 0) return ~bound >= minimum;
            if (bound >= minimum) return true;
            int found = sharedRun(gray, width, first * width, (first + 1) * width, minimum);
            bounds[first] = found == minimum ? found : ~found;
            return found == minimum;
        }
    }

    /** A lower-voice fallback must not reinterpret a moving note's downward flag. */
    static boolean noteStem(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int minY,
            float gap,
            float noteX,
            float noteY) {
        if (gray == null
                || gray.length != (long) width * height
                || gap < 6
                || !Float.isFinite(gap)
                || !Float.isFinite(noteX)
                || !Float.isFinite(noteY)) return false;
        int from = Math.max(0, Math.round(noteY + gap * .2f)),
                to = Math.min(height - 1, Math.round(minY + gap * .4f));
        // A flag can start less than one gap from the oval. The separately
        // required full-gap continuous shaft remains the ownership proof.
        if (minY - noteY < gap * .65f || minY - noteY > gap * 4.5f || to - from < gap) return false;
        int first =
                Math.max(0, Math.max(left - Math.round(gap * .25f), Math.round(noteX - gap * .8f)));
        int last = Math.min(width - 1, Math.min(right, Math.round(noteX + gap * .65f)));
        for (int x = first; x <= last; x++) {
            int dark = 0, blank = 0, longestBlank = 0;
            for (int y = from; y <= to; y++) {
                if ((gray[y * width + x] & 255) < 170) {
                    dark++;
                    blank = 0;
                } else longestBlank = Math.max(longestBlank, ++blank);
            }
            if (dark >= (to - from + 1) * .85f
                    && longestBlank <= Math.max(1, Math.round(gap * .15f))) return true;
        }
        return false;
    }

    /** Mirror the ownership proof for an upward flag beside a raised voice rest. */
    static boolean noteStemAbove(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int maxY,
            float gap,
            float noteX,
            float noteY) {
        if (gray == null
                || gray.length != (long) width * height
                || gap < 6
                || !Float.isFinite(gap)
                || !Float.isFinite(noteX)
                || !Float.isFinite(noteY)) return false;
        int from = Math.max(0, Math.round(maxY - gap * .4f)),
                to = Math.min(height - 1, Math.round(noteY - gap * .2f));
        if (noteY - maxY < gap * .65f || noteY - maxY > gap * 4.5f || to - from < gap) return false;
        int first = Math.max(0, Math.max(left, Math.round(noteX - gap * .65f)));
        int last =
                Math.min(
                        width - 1,
                        Math.min(right + Math.round(gap * .25f), Math.round(noteX + gap * .8f)));
        for (int x = first; x <= last; x++) {
            int dark = 0, blank = 0, longestBlank = 0;
            for (int y = from; y <= to; y++) {
                if ((gray[y * width + x] & 255) < 170) {
                    dark++;
                    blank = 0;
                } else longestBlank = Math.max(longestBlank, ++blank);
            }
            if (dark >= (to - from + 1) * .85f
                    && longestBlank <= Math.max(1, Math.round(gap * .15f))) return true;
        }
        return false;
    }
}
