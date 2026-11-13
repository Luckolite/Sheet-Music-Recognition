// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Estimates photographed paper tone without changing the supplied image. */
final class RestPaperTone {
    private RestPaperTone() {}

    static byte[] normalize(byte[] gray, int width, int height, float gap) {
        return normalize(gray, width, height, gap, 340f);
    }

    /** Ordinary rest connectors need a softer paper target than isolated dark contours. */
    static byte[] normalizeOrdinaryRestInk(byte[] gray, int width, int height, float gap) {
        return normalize(gray, width, height, gap, 220f);
    }

    /** Keep separate complete contour readings when blur changes a thin rule edge. */
    static byte[] normalizeOrdinaryRestInk(
            byte[] gray, int width, int height, float gap, float paperLevel) {
        if (!Float.isFinite(paperLevel) || paperLevel < 120 || paperLevel > 340) return gray;
        return normalize(gray, width, height, gap, paperLevel);
    }

    private static byte[] normalize(
            byte[] gray, int width, int height, float gap, float paperLevel) {
        var estimate = estimate(gray, width, height, gap);
        return estimate == null ? gray : estimate.normalize(paperLevel);
    }

    /** Reuse a paper estimate while deriving multiple tones from the same raster. */
    static Estimate estimate(byte[] gray, int width, int height, float gap) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || !Float.isFinite(gap)
                || gap <= 0) return null;
        int tile = Math.max(32, Math.min(128, Math.round(gap * 4)));
        int columns = (width - 1) / tile + 1, rows = (height - 1) / tile + 1;
        int[] paper = new int[columns * rows];
        boolean shaded = false, uniform = true;
        int[] histogram = new int[256];
        for (int row = 0; row < rows; row++) {
            int top = row * tile, bottom = top + Math.min(tile, height - top);
            int firstRow = top * width, endRow = bottom * width;
            for (int col = 0; col < columns; col++) {
                int left = col * tile, right = left + Math.min(tile, width - left);
                int tone = tileTone(
                        gray, width, firstRow, endRow, bottom - top, left, right, histogram);
                paper[row * columns + col] = tone;
                uniform &= tone == paper[0];
                shaded |= tone < 240;
            }
        }
        if (!shaded) return null;
        int uniformTone = uniform ? paper[0] : 0;
        return new Estimate(gray, width, height, tile, columns, rows, paper, uniformTone);
    }

    record Estimate(byte[] gray, int width, int height, int tile, int columns, int rows, int[] paper,
            int uniformTone, HorizontalGrid horizontal) {
        Estimate(byte[] gray, int width, int height, int tile, int columns, int rows, int[] paper,
                int uniformTone) {
            this(gray, width, height, tile, columns, rows, paper, uniformTone,
                    uniformTone != 0 || width > 16384
                            ? null : new HorizontalGrid(width, tile, columns, rows, paper));
        }

        byte[] normalize(float paperLevel) {
            HorizontalGrid grid = horizontal;
            if (uniformTone != 0) {
                boolean exactBlend = (tile & (tile - 1)) == 0 || (columns == 1 && rows == 1);
                byte[] result = normalizeUniform(gray, uniformTone, paperLevel, exactBlend);
                if (result != null) return result;
                if (width <= 16384 && paperLevel >= 120 && paperLevel <= 340)
                    grid = new HorizontalGrid(width, tile, columns, rows, paper);
            }
            return interpolate(gray, width, height, tile, columns, rows, paper, grid, paperLevel);
        }
    }

    private static byte[] normalizeUniform(
            byte[] gray, int tone, float paperLevel, boolean exactBlend) {
        if (!exactBlend && !(paperLevel >= 120 && paperLevel <= 340)) return null;
        byte[] conversions = new byte[256];
        // Each blend can lose a few rounding bits across its complement, products,
        // and sum. Both blends together stay within four ULPs of the integer tone.
        float error = exactBlend ? 0 : 4 * Math.ulp((float) tone);
        float lightest = tone + error, darkest = tone - error;
        for (int ink = 0; ink < conversions.length; ink++) {
            float scaled = ink * paperLevel;
            int value = Math.min(255, Math.round(scaled / lightest));
            if (!exactBlend && value < 255 && value != Math.min(255, Math.round(scaled / darkest)))
                return null;
            conversions[ink] = (byte) value;
        }
        byte[] result = new byte[gray.length];
        for (int pixel = 0; pixel < gray.length; pixel++)
            result[pixel] = conversions[gray[pixel] & 255];
        return result;
    }

    private static final class HorizontalGrid {
        final float[] leftWeight, rightWeight;
        final int[] columnStarts, firstActive, endActive;
        final int[] fillRow;

        HorizontalGrid(int width, int tile, int columns, int rows, int[] paper) {
            leftWeight = new float[width];
            rightWeight = new float[width];
            columnStarts = new int[columns + 1];
            int nextColumn = 0;
            for (int x = 0; x < width; x++) {
                float gx = Math.max(0, Math.min(columns - 1, (x + .5f) / tile - .5f));
                int x0 = (int) gx;
                rightWeight[x] = gx - x0;
                leftWeight[x] = 1 - rightWeight[x];
                while (nextColumn <= x0) columnStarts[nextColumn++] = x;
            }
            java.util.Arrays.fill(columnStarts, nextColumn, columnStarts.length, width);
            firstActive = new int[rows];
            endActive = new int[rows];
            fillRow = new int[rows];
            for (int row = 0; row < rows; row++) {
                int offset = row * columns;
                if (row > 0 && java.util.Arrays.equals(paper, offset - columns, offset,
                        paper, offset, offset + columns)) {
                    fillRow[row] = fillRow[row - 1];
                    firstActive[row] = firstActive[row - 1];
                    endActive[row] = endActive[row - 1];
                    continue;
                }
                fillRow[row] = row;
                int first = 0, last = columns - 1;
                // Leave margin for rounding in both interpolation steps.
                while (first < columns && paper[offset + first] >= 241) first++;
                if (first == columns) {
                    firstActive[row] = width;
                    continue;
                }
                while (paper[offset + last] >= 241) last--;
                firstActive[row] = columnStarts[Math.max(0, first - 1)];
                endActive[row] = columnStarts[last + 1];
            }
        }

        void fill(float[] row, int[] paper, int offset) {
            int columns = columnStarts.length - 1;
            for (int col = 0; col < columns; col++) {
                int left = paper[offset + col], right = paper[offset + Math.min(col + 1, columns - 1)];
                int end = columnStarts[col + 1];
                for (int x = columnStarts[col]; x < end; x++)
                    row[x] = left * leftWeight[x] + right * rightWeight[x];
            }
        }
    }

    private static byte[] interpolate(
            byte[] gray, int width, int height, int tile, int columns, int rows, int[] paper,
            HorizontalGrid horizontal, float paperLevel) {
        byte[] result = gray.clone();
        // Keep general rounding for levels outside the supported normalization range.
        if (horizontal == null || !(paperLevel >= 120 && paperLevel <= 340))
            return interpolateWide(
                    gray, width, height, tile, columns, rows, paper, result, paperLevel);
        float[] upperRow = new float[width], lowerRow = new float[width];
        float[] scaledInk = new float[256];
        for (int ink = 0; ink < scaledInk.length; ink++) scaledInk[ink] = ink * paperLevel;
        int upperRowIndex = -1, lowerRowIndex = -1;
        for (int y = 0, offset = 0; y < height; y++, offset += width) {
            float gy = Math.max(0, Math.min(rows - 1, (y + .5f) / tile - .5f));
            int y0 = (int) gy;
            float fy = gy - y0;
            int y1 = fy == 0 ? y0 : y0 + 1;
            int upperFill = horizontal.fillRow[y0], lowerFill = horizontal.fillRow[y1];
            int start = Math.min(horizontal.firstActive[y0], horizontal.firstActive[y1]);
            int end = Math.max(horizontal.endActive[y0], horizontal.endActive[y1]);
            if (start >= end) continue;
            float upperWeight = 1 - fy;
            if (upperRowIndex != upperFill && lowerRowIndex == upperFill) {
                float[] previousUpper = upperRow;
                upperRow = lowerRow;
                lowerRow = previousUpper;
                lowerRowIndex = upperRowIndex;
                upperRowIndex = upperFill;
            }
            if (upperRowIndex != upperFill) {
                horizontal.fill(upperRow, paper, upperFill * columns);
                upperRowIndex = upperFill;
            }
            if (upperFill != lowerFill && lowerRowIndex != lowerFill) {
                horizontal.fill(lowerRow, paper, lowerFill * columns);
                lowerRowIndex = lowerFill;
            }
            if (fy == 0) {
                for (int x = start, pixel = offset + start; x < end; x++, pixel++) {
                    float tone = upperRow[x];
                    if (tone < 240)
                        result[pixel] = normalizedPixel(scaledInk[gray[pixel] & 255], tone);
                }
                continue;
            }
            // Keep the vertical blend even when both horizontal rows match.
            float[] lowerTones = upperFill == lowerFill ? upperRow : lowerRow;
            for (int x = start, pixel = offset + start; x < end; x++, pixel++) {
                float upper = upperRow[x], lower = lowerTones[x];
                float tone = upper * upperWeight + lower * fy;
                if (tone < 240)
                    result[pixel] = normalizedPixel(scaledInk[gray[pixel] & 255], tone);
            }
        }
        return result;
    }

    private static byte normalizedPixel(float scaledInk, float tone) {
        float scaled = scaledInk / tone;
        // Adding in double preserves the half-integer decision of Math.round(float).
        return (byte) Math.min(255, (int) (scaled + 0.5d));
    }

    private static int tileTone(
            byte[] gray, int width, int firstRow, int endRow, int rows,
            int left, int right, int[] histogram) {
        int tileWidth = right - left;
        int count = rows * tileWidth;
        int upperRank = count / 4 + 1;
        // Sample separated locations before attempting either exact early exit.
        int first = gray[firstRow + left] & 255;
        int middle = firstRow + (rows / 2) * width + left + tileWidth / 2;
        int last = endRow - width + right - 1;
        if (first == 255 && gray[middle] == -1 && gray[last] == -1
                && hasWhiteRank(gray, width, firstRow, endRow, left, right, upperRank))
            return 255;
        if (first < 96 && (gray[middle] & 255) < 96 && (gray[last] & 255) < 96
                && hasDarkRank(gray, width, firstRow, endRow, left, right,
                        count - upperRank + 1, count)) return 255;
        // A bright tile still contributes its exact tone to neighbouring shaded tiles.
        java.util.Arrays.fill(histogram, 0);
        if (tileWidth == width) {
            for (int pixel = firstRow; pixel < endRow; pixel++) histogram[gray[pixel] & 255]++;
        } else {
            for (int row = firstRow; row < endRow; row += width)
                for (int pixel = row + left, end = row + right; pixel < end; pixel++)
                    histogram[gray[pixel] & 255]++;
        }
        for (int tone = 255; tone >= 96; tone--) {
            upperRank -= histogram[tone];
            if (upperRank <= 0) return tone;
        }
        return 255;
    }

    private static boolean hasWhiteRank(
            byte[] gray, int width, int firstRow, int endRow, int left, int right, int needed) {
        int tileWidth = right - left;
        for (int row = firstRow; row < endRow; row += width) {
            int hits = 0;
            for (int pixel = row + left, end = row + right; pixel < end; pixel++)
                hits += ((gray[pixel] & 255) - 255) >>> 31;
            int white = tileWidth - hits;
            needed -= white;
            if (needed <= 0) return true;
            // Use the histogram if these samples overestimated white coverage.
            if (white * 2 < tileWidth) return false;
        }
        return false;
    }

    private static boolean hasDarkRank(
            byte[] gray, int width, int firstRow, int endRow, int left, int right,
            int needed, int remaining) {
        int tileWidth = right - left;
        for (int row = firstRow; row < endRow; row += width) {
            int hits = 0;
            for (int pixel = row + left, end = row + right; pixel < end; pixel++)
                hits += ((gray[pixel] & 255) - 96) >>> 31;
            needed -= hits;
            if (needed <= 0) return true;
            remaining -= tileWidth;
            if (needed > remaining) return false;
        }
        return false;
    }

    private static byte[] interpolateWide(
            byte[] gray,
            int width,
            int height,
            int tile,
            int columns,
            int rows,
            int[] paper,
            byte[] result,
            float paperLevel) {
        for (int y = 0; y < height; y++) {
            float gy = Math.max(0, Math.min(rows - 1, (y + .5f) / tile - .5f));
            int y0 = (int) gy, y1 = Math.min(rows - 1, y0 + 1);
            int upperRow = y0 * columns, lowerRow = y1 * columns;
            float fy = gy - y0;
            float upperWeight = 1 - fy;
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                float gx = Math.max(0, Math.min(columns - 1, (x + .5f) / tile - .5f));
                int x0 = (int) gx, x1 = Math.min(columns - 1, x0 + 1);
                float fx = gx - x0;
                float upper = paper[upperRow + x0] * (1 - fx) + paper[upperRow + x1] * fx;
                float lower = paper[lowerRow + x0] * (1 - fx) + paper[lowerRow + x1] * fx;
                float tone = upper * upperWeight + lower * fy;
                if (tone < 240)
                    result[offset + x] =
                            (byte) Math.min(255, Math.round((gray[offset + x] & 255) * paperLevel / tone));
            }
        }
        return result;
    }
}
