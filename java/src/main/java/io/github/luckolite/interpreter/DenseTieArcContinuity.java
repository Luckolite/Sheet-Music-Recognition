// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Original-column connectivity inside an already qualified tie curve. */
final class DenseTieArcContinuity {
    private static final java.lang.invoke.VarHandle GRAY_WORDS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                    long[].class, java.nio.ByteOrder.nativeOrder());
    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long BYTE_ONES = 0x0101010101010101L;

    static final class StraightRows {
        private final byte[] gray;
        private final int width, left, right, inkLimit, first;
        private final byte[] states;
        private final float minimum;
        private int[] previousFrontier, skippedFrontier, nextFrontier;
        private float[] flatColumns;
        private int flatColumnsReady;

        StraightRows(byte[] gray, int width, int left, int right, int inkLimit, int first, int last) {
            this.gray = gray;
            this.width = width;
            this.left = left;
            this.right = right;
            this.inkLimit = inkLimit;
            this.first = first;
            this.states = new byte[Math.max(0, last - first + 1)];
            minimum = (right - left + 1) * .85f;
        }

        private void ensureFrontiers(int capacity) {
            if (previousFrontier != null && previousFrontier.length >= capacity) return;
            previousFrontier = new int[capacity];
            skippedFrontier = new int[capacity];
            nextFrontier = new int[capacity];
        }

        float flatColumn(int x, int scanLeft, int scanRight) {
            int column = x - scanLeft;
            if (scanLeft != left || scanRight != right || right - left >= 4096)
                return flatProfile(column / (float) (scanRight - scanLeft));
            if (flatColumns == null) flatColumns = new float[right - left + 1];
            // Scans start at the first column and can stop at a disconnected shoulder.
            while (flatColumnsReady <= column) {
                int next = flatColumnsReady++;
                flatColumns[next] = flatProfile(next / (float) (right - left));
            }
            return flatColumns[column];
        }

        boolean contains(int y) {
            int index = y - first;
            if (index < 0 || index >= states.length) return false;
            byte state = states[index];
            if (state != 0) return state == 2;
            int dark = 0, at = y * width + left, end = y * width + right;
            if (end - at >= 23 && inkLimit >= 0 && inkLimit < 255) {
                int cutoff = inkLimit + 1;
                long bias = BYTE_ONES * (cutoff & 127);
                for (; at <= end - 7; at += Long.BYTES) {
                    long word = (long) GRAY_WORDS.get(gray, at);
                    long high = word & HIGH_BITS;
                    long brightLow = ((word | HIGH_BITS) - bias) & HIGH_BITS;
                    long bright = cutoff < 128 ? brightLow | high : brightLow & high;
                    dark += Long.BYTES - Long.bitCount(bright);
                    if (dark >= minimum || dark + (end - at - 7) < minimum) {
                        at = end + 1;
                        break;
                    }
                }
            }
            for (; at <= end; at++) {
                if ((gray[at] & 255) <= inkLimit && ++dark >= minimum) break;
                if (dark + (end - at) < minimum) break;
            }
            boolean covered = dark >= minimum;
            states[index] = (byte) (covered ? 2 : 1);
            return covered;
        }
    }

    static boolean proved(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            float centerY,
            float gap,
            int side,
            float offset,
            float bend,
            boolean flat,
            int inkLimit,
            boolean contrast,
            StraightRows straightRows,
            int[] contrastOffsets) {
        if (left < 0
                || right >= width
                || right <= left
                || gap < 2
                || gray == null
                || labels == null
                || gray.length != (long) width * height
                || labels.length != gray.length) return false;
        int radius = Math.max(1, Math.round(gap * .1f));
        int top = Math.max(0, (int) Math.floor(centerY - gap * 3.3f - radius));
        int bottom = Math.min(height - 1, (int) Math.ceil(centerY + gap * 3.3f + radius));
        if (bottom < top) return false;
        int count = bottom - top + 1, rise = Math.max(1, (int) Math.ceil(gap * .1f) + 1);
        int capacity = (int) Math.min(count, 2L * radius + 1);
        // Candidate searches sharing these row proofs can reuse storage. Only each
        // frontier's populated prefix is read, and sizes start at zero for every search.
        straightRows.ensureFrontiers(capacity);
        int[] previous = straightRows.previousFrontier;
        int[] skipped = straightRows.skippedFrontier;
        int[] next = straightRows.nextFrontier;
        int previousSize = 0, skippedSize = 0;
        int covered = 0;
        int span = right - left + 1;
        int edgeSlack = Math.min(Math.round(gap * 1.4f), (int) Math.floor(span * .15f));
        boolean started = false;
        boolean previousConnected = false;
        int reach = Math.max(2, Math.round(gap * .4f));
        float maximumThickness = gap * .6f;
        for (int x = left; x <= right; x++) {
            int nextSize = 0, previousCursor = 0, skippedCursor = 0;
            float t = (x - left) / (float) (right - left);
            float profile = flat ? straightRows.flatColumn(x, left, right) : 4 * t * (1 - t);
            int requestedY = Math.round(centerY + side * gap * (offset + bend * profile));
            for (int y = Math.max(top, requestedY - radius);
                    y <= Math.min(bottom, requestedY + radius);
                    y++) {
                int at = y * width + x, shade = gray[at] & 255;
                if (shade > inkLimit || labels[at] == OmrMeasurePostProcessor.NOTEHEAD) continue;
                if (contrast && shade > 125) {
                    int paper = 0;
                    for (int distance : contrastOffsets) {
                        if (y >= distance)
                            paper = Math.max(paper, gray[(y - distance) * width + x] & 255);
                        if (y + distance < height)
                            paper = Math.max(paper, gray[(y + distance) * width + x] & 255);
                        if (paper >= shade + 20) break;
                    }
                    if (paper < shade + 20) continue;
                }
                // A staff crossing may be obscured only by real raw ink at this column.
                // Outside a rule, thick vertical objects cannot supply the curve corridor.
                if (!straightRows.contains(y)) {
                    int inkTop = y, inkBottom = y;
                    int firstRow = Math.max(0, y - reach);
                    int pixel = at - width;
                    while (inkTop > firstRow && (gray[pixel] & 255) <= inkLimit) {
                        inkTop--;
                        pixel -= width;
                    }
                    if (y - inkTop > maximumThickness) continue;
                    int lastRow = Math.min(height - 1, y + reach);
                    // The first excess row rejects this stroke without scanning its far edge.
                    if (maximumThickness < (1 << 24) && lastRow - inkTop > maximumThickness)
                        lastRow = inkTop + (int) maximumThickness + 1;
                    pixel = at + width;
                    while (inkBottom < lastRow && (gray[pixel] & 255) <= inkLimit) {
                        inkBottom++;
                        pixel += width;
                    }
                    if (inkBottom - inkTop > maximumThickness) continue;
                }
                // Both frontiers and candidate rows are ordered, so cursors only advance.
                while (previousCursor < previousSize && previous[previousCursor] < y - rise)
                    previousCursor++;
                while (skippedCursor < skippedSize && skipped[skippedCursor] < y - rise * 2)
                    skippedCursor++;
                if (!started
                        || (previousCursor < previousSize && previous[previousCursor] <= y + rise)
                        || (skippedCursor < skippedSize && skipped[skippedCursor] <= y + rise * 2))
                    next[nextSize++] = y;
            }
            boolean connected = nextSize != 0;
            // One column of missing raster ink is tolerated; two consecutive empty
            // columns cannot inherit a frontier. No semantic label invents pixels.
            if (!connected && !previousConnected) {
                if (!started) {
                    if (x - left >= edgeSlack) return false;
                    continue;
                }
                // The existing head-clearance proofs allow detached printed ends.
                // Trim only the bounded outer shoulder; never restart after an interior gap.
                return right - x <= edgeSlack && covered >= span * .8f;
            }
            started |= connected;
            if (connected) covered++;
            previousConnected = connected;
            int[] reusable = skipped;
            skipped = previous;
            skippedSize = previousSize;
            previous = next;
            previousSize = nextSize;
            next = reusable;
        }
        return covered >= (right - left + 1) * .8f;
    }

    private static final class FlatProfilePrefix {
        static final int INTERVALS = 512;
        static final double[] KNOTS = knots();

        private static double[] knots() {
            double[] result = new double[INTERVALS - 1];
            for (int i = 0; i < result.length; i++) {
                result[i] = flatProfileX((i + 1) / (double) INTERVALS);
            }
            return result;
        }
    }

    private static double flatProfileX(double u) {
        double v = 1 - u;
        return 3 * .05 * v * v * u + 3 * .95 * v * u * u + u * u * u;
    }

    private static float flatProfile(float t) {
        // The first nine bisections land on this dyadic grid. A tie
        // chooses the lower interval, as in the original bisection.
        if (Float.isNaN(t)) t = Float.NEGATIVE_INFINITY;
        int index = java.util.Arrays.binarySearch(FlatProfilePrefix.KNOTS, (double) t);
        int interval = index < 0 ? -index - 1 : index;
        double low = interval / (double) FlatProfilePrefix.INTERVALS;
        double high = low + 1d / FlatProfilePrefix.INTERVALS;
        for (int step = 0; step < 7; step++) {
            double u = (low + high) * .5;
            if (flatProfileX(u) < t) low = u;
            else high = u;
        }
        double u = (low + high) * .5;
        return (float) (4 * u * (1 - u));
    }
}
