// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Connected rest components inside a merged vertical projection. */
final class SeparatedRestInk {
    record Body(int left, int right, int top, int bottom, int[] pixels) {
        Body(int left, int right, int top, int bottom) {
            this(left, right, top, bottom, null);
        }

        byte[] owned(
                byte[] gray, byte[] scratch, int width, int height, boolean[] line, int lineTop) {
            if (pixels == null) return gray;
            int span = right - left + 1;
            boolean[] member = new boolean[span * (bottom - top + 1)];
            for (int pixel : pixels) {
                int y = pixel / width;
                member[(y - top) * span + pixel - y * width - left] = true;
            }
            for (int y = Math.max(0, top - 1); y <= Math.min(height - 1, bottom + 1); y++) {
                if (y >= lineTop && y - lineTop < line.length && line[y - lineTop]) continue;
                int raster = y * width + left;
                if (y < top || y > bottom) {
                    Arrays.fill(scratch, raster, raster + span, (byte) 255);
                    continue;
                }
                int memberRow = (y - top) * span;
                for (int x = 0; x < span; x++)
                    if (!member[memberRow + x]) scratch[raster + x] = (byte) 255;
            }
            return scratch;
        }

        void restore(byte[] scratch, byte[] gray, int width, int height) {
            if (pixels == null) return;
            int span = right - left + 1;
            int first = Math.max(0, top - 1), last = Math.min(height - 1, bottom + 1);
            for (int y = first, row = first * width + left; y <= last; y++, row += width)
                System.arraycopy(gray, row, scratch, row, span);
        }
    }

    static boolean represented(
            Body body,
            SixteenthRestDetector.Staff staff,
            List<MeasureRegion> measures,
            List<ScoreRestEvent> rests,
            int width,
            int height) {
        float x = (body.left() + body.right()) * .5f, y = (body.top() + body.bottom()) * .5f;
        float horizontalTolerance = staff.gap() * .65f;
        float minimumVerticalTolerance =
                Math.max(staff.gap() * .5f, (body.bottom() - body.top() + 1) * .5f);
        for (var rest : rests) {
            if (rest.staffIndex() != staff.index()
                    || rest.staffCount() != staff.count()
                    || rest.measureIndex() < 0
                    || rest.measureIndex() >= measures.size()) continue;
            var measure = measures.get(rest.measureIndex());
            float restX =
                    (measure.left() + rest.positionInMeasure() * (measure.right() - measure.left()))
                            * width;
            if (Math.abs(restX - x) < horizontalTolerance
                    && Math.abs(rest.pageY() * height - y)
                            < Math.max(minimumVerticalTolerance, rest.pageHeight() * height * .5f))
                return true;
        }
        return false;
    }

    static List<Body> find(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int top,
            int bottom,
            boolean[] line,
            float gap) {
        if (gray == null
                || gray.length != (long) width * height
                || gap < 4
                || left < 0
                || right >= width
                || top < 0
                || bottom >= height
                || right < left
                || bottom < top
                || line.length != bottom - top + 1
                || right - left + 1 > gap * 6) return List.of();
        int span = right - left + 1;
        float minimumWidth = gap * .7f, minimumHeight = gap * 1.3f;
        if (span < minimumWidth || bottom - top - 1 < minimumHeight) return List.of();
        int firstVisible = top, lastVisible = bottom;
        while (firstVisible <= bottom && line[firstVisible - top]) firstVisible++;
        while (lastVisible >= top && line[lastVisible - top]) lastVisible--;
        if (lastVisible - firstVisible - 1 < minimumHeight) return List.of();
        double maximumStep = Math.ceil(gap * .4f) + 1;
        int rows = lastVisible - firstVisible + 1;
        float maximumWidth = gap * 1.6f, maximumHeight = gap * 3.6f;
        int size = span * rows;
        // The padded area is less than twice the crop area, so coordinates fit in an unsigned int.
        int columnBits = Integer.SIZE - Integer.numberOfLeadingZeros(span - 1);
        int columnMask = (1 << columnBits) - 1;
        boolean[] visited = null;
        int[] queue = null;
        int[] crossingRows = null;
        int lineOffset = firstVisible - top;
        int rasterBase = firstVisible * width + left;
        List<Body> result = null;
        for (int seedRow = 0; seedRow < rows; seedRow++) {
            // A later seed cannot leave enough space above the final visible row.
            if (rows - 1 - seedRow < minimumHeight) break;
            if (line[lineOffset + seedRow]) continue;
            int rasterRow = rasterBase + seedRow * width;
            for (int sx = 0, seed = seedRow * span; sx < span; sx++, seed++) {
                if ((visited != null && visited[seed]) || (gray[rasterRow + sx] & 255) >= 170)
                    continue;
                if (visited == null) {
                    visited = new boolean[size];
                    queue = new int[size];
                    int firstMasked = 0;
                    while (firstMasked < rows && !line[lineOffset + firstMasked])
                        firstMasked++;
                    if (firstMasked < rows) {
                        crossingRows = new int[rows * 2];
                        // Only the nearest visible row can be reached in each direction.
                        int previous = -1;
                        for (int row = 0; row < rows; row++) {
                            if (line[lineOffset + row]) continue;
                            crossingRows[row * 2] = -1;
                            crossingRows[row * 2 + 1] = -1;
                            if (previous >= 0 && !(row - previous > maximumStep)) {
                                crossingRows[row * 2] = previous;
                                crossingRows[previous * 2 + 1] = row;
                            }
                            previous = row;
                        }
                    }
                }
                int count = 1, cursor = 0;
                queue[0] = (seedRow << columnBits) | sx;
                visited[seed] = true;
                int l = span - 1, r = 0, b = seedRow;
                boolean eligible = seedRow > 0;
                while (cursor < count) {
                    int pixel = queue[cursor++], row = pixel >>> columnBits;
                    int x = pixel & columnMask;
                    if (eligible) {
                        l = Math.min(l, x);
                        r = Math.max(r, x);
                        b = Math.max(b, row);
                        eligible = b < rows - 1 && !(r - l + 1 > maximumWidth)
                                && !(b - seedRow + 1 > maximumHeight);
                    }
                    for (int direction = -1; direction <= 1; direction++) {
                        int neighborRow = crossingRows == null || direction == 0 ? row + direction
                                : crossingRows[row * 2 + (direction > 0 ? 1 : 0)];
                        if (neighborRow < 0 || neighborRow >= rows) continue;
                        int targetRow = neighborRow * span;
                        int targetRaster = rasterBase + neighborRow * width;
                        int packedRow = neighborRow << columnBits;
                        int reach = direction == 0 ? 1 : (neighborRow - row) * direction;
                        int endColumn = Math.min(span - 1, x + reach);
                        for (int xx = Math.max(0, x - reach); xx <= endColumn; xx++) {
                            int next = targetRow + xx;
                            if (visited[next]) continue;
                            visited[next] = true;
                            if ((gray[targetRaster + xx] & 255) < 170) {
                                queue[count++] = packedRow | xx;
                            }
                        }
                    }
                }
                // Never crop a continuing stem or a band-edge glyph into a complete rest.
                // Raster-order seeding gives the highest row of the connected component.
                if (!eligible || r - l + 1 < minimumWidth || b - seedRow + 1 < minimumHeight)
                    continue;
                int[] pixels = new int[count];
                for (int i = 0; i < count; i++) {
                    int pixel = queue[i], row = pixel >>> columnBits;
                    pixels[i] = rasterBase + row * width + (pixel & columnMask);
                }
                if (result == null) result = new ArrayList<>();
                result.add(new Body(left + l, left + r, firstVisible + seedRow, firstVisible + b, pixels));
            }
        }
        return result == null ? List.of() : List.copyOf(result);
    }
}
