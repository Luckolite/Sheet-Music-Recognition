// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Attached printed shafts distinguish opposing voices without guessing from pitch. */
final class PrintedStemDirection {
    private static final int EXACT_FLOAT_INTEGER_LIMIT = 1 << 24;

    private PrintedStemDirection() {}

    static int detect(byte[] gray, int width, int height, float x, float y, float gap) {
        return detect(gray, width, height, x, y, gap, null);
    }

    static int detect(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float gap,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        if (gray == null || gray.length != (long) width * height || !Float.isFinite(gap) || gap < 4)
            return 0;
        boolean up, down;
        if (quarterBudget == null) {
            float start = gap * .15f, halfWidth = gap * .2f, edgeOffset = gap * .6f;
            int lastStep = Math.round(gap * 1.9f);
            float minimum = gap * 1.7f;
            if ((long) lastStep + 1 < minimum) return 0;
            // Quarter-pixel operands and every intermediate sum are exact within these bounds.
            boolean exactStride = lastStep < (1 << 20) && start < (1 << 20)
                    && Math.abs(y) < (1 << 20)
                    && start * 4 == (int) (start * 4) && y * 4 == (int) (y * 4);
            up = shaft(gray, width, height, x + edgeOffset, y, halfWidth, start,
                    lastStep, minimum, exactStride, -1);
            down = shaft(gray, width, height, x - edgeOffset, y, halfWidth, start,
                    lastStep, minimum, exactStride, 1);
        } else {
            up = budgetedShaft(gray, width, height, x, y, gap, -1, quarterBudget);
            down = budgetedShaft(gray, width, height, x, y, gap, 1, quarterBudget);
        }
        return up == down ? 0 : up ? 1 : -1;
    }

    private static boolean shaft(
            byte[] gray, int width, int height, float edge, float y, float halfWidth,
            float start, int lastStep, float minimum, boolean exactStride, int direction) {
        float lastCol = edge + halfWidth;
        int firstCol = Math.max(0, Math.round(edge - halfWidth));
        if (firstCol >= width || !(firstCol <= lastCol)) return false;
        int firstRow = shaftRow(y, start, direction, 0);
        if (firstRow < 0 || firstRow >= height) return false;
        if (exactStride) {
            int available = direction < 0 ? firstRow : height - 1 - firstRow;
            int total = Math.min(lastStep, available) + 1;
            if (total < minimum) return false;
            int neededHits = ceilPositive(total * .92f);
            return shaftAtStride(gray, firstCol, lastProbeColumn(width, lastCol), firstRow * width,
                    direction * width, neededHits, total - neededHits);
        }
        int endRow = shaftRow(y, start, direction, lastStep);
        if (endRow < 0 || endRow >= height) {
            // Rounded rows are monotone, so find the first step outside the raster once.
            int inside = minimum < EXACT_FLOAT_INTEGER_LIMIT ? ceilPositive(minimum) - 1 : 0;
            int minimumRow = inside == 0 ? firstRow : shaftRow(y, start, direction, inside);
            if (minimumRow < 0 || minimumRow >= height) return false;
            int outside = lastStep;
            int boundary = direction < 0 ? 0 : height - 1;
            while (outside - inside > 1) {
                int middle = inside + (outside - inside) / 2;
                int probe = shaftRow(y, start, direction, middle);
                if (direction < 0 ? probe >= boundary : probe <= boundary) inside = middle;
                else outside = middle;
            }
            lastStep = inside;
            if ((long) lastStep + 1 < minimum) return false;
        }
        int colLimit = lastProbeColumn(width, lastCol);
        long total = (long) lastStep + 1;
        float required = total * .92f;
        if (total <= EXACT_FLOAT_INTEGER_LIMIT) {
            int neededHits = ceilPositive(required);
            int allowedMisses = (int) total - neededHits;
            int firstRowOffset = firstRow * width;
            for (int col = firstCol; col <= colLimit; col++) {
                int hits = neededHits, misses = allowedMisses;
                int at = firstRowOffset + col;
                for (int step = 0; ; step++) {
                    if ((gray[at] & 255) < 165) {
                        if (--hits == 0) return true;
                    } else if (--misses < 0) {
                        break;
                    }
                    at = shaftRow(y, start, direction, step + 1) * width + col;
                }
            }
            return false;
        }
        int firstRowOffset = firstRow * width;
        for (int col = firstCol; col <= colLimit; col++) {
            int hits = 0;
            int at = firstRowOffset + col;
            for (int step = 0; ; step++) {
                if ((gray[at] & 255) < 165) hits++;
                if (hits >= required) return true;
                if (step == lastStep || hits + (long) (lastStep - step) < required) break;
                at = shaftRow(y, start, direction, step + 1) * width + col;
            }
        }
        return false;
    }

    private static int lastProbeColumn(int width, float lastCol) {
        int limit = Math.min(width - 1, (int) lastCol);
        if (limit < EXACT_FLOAT_INTEGER_LIMIT || limit == width - 1) return limit;
        // Find the last integer that rounds to this float or below.
        double midpoint = ((double) lastCol + Math.nextUp(lastCol)) * .5;
        int candidate = Math.min(width - 1, (int) midpoint);
        return candidate <= lastCol ? candidate : candidate - 1;
    }

    private static boolean shaftAtStride(
            byte[] gray, int firstCol, int lastCol, int firstRowOffset,
            int stride, int neededHits, int allowedMisses) {
        for (int col = firstCol; col <= lastCol; col++) {
            int hits = neededHits, misses = allowedMisses;
            for (int at = firstRowOffset + col; ; at += stride) {
                if ((gray[at] & 255) < 165) {
                    if (--hits == 0) return true;
                } else if (--misses < 0) {
                    break;
                }
            }
        }
        return false;
    }

    private static int shaftRow(float y, float start, int direction, int step) {
        return Math.round(y + direction * (start + step));
    }

    private static int ceilPositive(float value) {
        int floor = (int) value;
        return floor < value ? floor + 1 : floor;
    }

    private static boolean budgetedShaft(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float gap,
            int direction,
            FaintQuarterRestInk.Budget quarterBudget) {
        FaintQuarterRestInk.charge(quarterBudget, 1, 0);
        float edge = x + (direction < 0 ? .6f : -.6f) * gap;
        float halfWidth = gap * .2f, lastCol = edge + halfWidth;
        float start = gap * .15f, minimum = gap * 1.7f;
        int lastStep = Math.round(gap * 1.9f);
        int firstCol = Math.round(edge - halfWidth);
        FaintQuarterRestInk.charge(quarterBudget, 1, 0);
        for (int col = firstCol; col <= lastCol; col++) {
            FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            if (col < 0 || col >= width) continue;
            int hits = 0, total = 0;
            FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            for (int step = 0; step <= lastStep; step++) {
                FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                int row = shaftRow(y, start, direction, step);
                if (row < 0 || row >= height) break;
                total++;
                if ((gray[row * width + col] & 255) < 165) hits++;
            }
            if (total >= minimum && hits >= total * .92f) return true;
        }

        return false;
    }
}
