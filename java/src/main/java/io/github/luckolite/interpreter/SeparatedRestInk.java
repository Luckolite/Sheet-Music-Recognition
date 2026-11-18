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
            if (span <= Long.SIZE) {
                maskOwnedRows(scratch, width, height, line, lineTop, span);
                return scratch;
            }
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

        private void maskOwnedRows(
                byte[] scratch, int width, int height, boolean[] line, int lineTop, int span) {
            long[] member = new long[bottom - top + 1];
            for (int pixel : pixels) {
                int y = pixel / width;
                member[y - top] |= 1L << (pixel - y * width - left);
            }
            long columns = -1L >>> (Long.SIZE - span);
            for (int y = Math.max(0, top - 1); y <= Math.min(height - 1, bottom + 1); y++) {
                if (y >= lineTop && y - lineTop < line.length && line[y - lineTop]) continue;
                int raster = y * width + left;
                long missing = y < top || y > bottom ? columns : columns & ~member[y - top];
                while (missing != 0) {
                    int start = Long.numberOfTrailingZeros(missing);
                    int end = start + Long.numberOfTrailingZeros(~(missing >>> start));
                    Arrays.fill(scratch, raster + start, raster + Math.min(end, span), (byte) 255);
                    if (end >= span) break;
                    missing &= -1L << end;
                }
            }
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

    static final class Scratch {
        private byte[] visited = new byte[0];
        private int[] queue = new int[0];
        private int[] crossingRows;
        private long[] remainingInk;

        private int[] queue(int size) {
            if (queue.length < size) queue = new int[size];
            return queue;
        }

        private void reset(int size) {
            if (visited.length < size) {
                visited = new byte[size];
            } else {
                Arrays.fill(visited, 0, size, (byte) 0);
            }
        }
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
        return find(gray, width, height, left, right, top, bottom, line, gap, new Scratch());
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
            float gap,
            Scratch scratch) {
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
        byte[] visited = null;
        int[] queue = null;
        int[] crossingRows = null;
        long[] remainingInk = null;
        int lineOffset = firstVisible - top;
        int rasterBase = firstVisible * width + left;
        List<Body> result = null;
        int segmentEnd = rows - 1;
        boolean masked = false;
        for (int row = 1, previous = 0; row < rows; row++) {
            if (line[lineOffset + row]) {
                masked = true;
            } else {
                if (row - previous > maximumStep) {
                    segmentEnd = previous;
                    break;
                }
                previous = row;
            }
        }
        for (int seedRow = 0; seedRow < rows; seedRow++) {
            if (rows - 1 - seedRow < minimumHeight) break;
            if (line[lineOffset + seedRow]) continue;
            if (seedRow > segmentEnd) {
                segmentEnd = seedRow;
                for (int row = seedRow + 1; row < rows && !(row - segmentEnd > maximumStep); row++)
                    if (!line[lineOffset + row]) segmentEnd = row;
            }
            if (segmentEnd < rows - 1 && segmentEnd - seedRow + 1 < minimumHeight) {
                seedRow = segmentEnd;
                continue;
            }
            int rasterRow = rasterBase + seedRow * width;
            int seedBase = seedRow * span;
            int scanColumn = 0, wordColumn = 0;
            long seeds = 0;
            while (remainingInk != null ? remainingInk[seedRow] != 0 : seeds != 0 || scanColumn < span) {
                int sx;
                if (remainingInk != null) {
                    sx = Long.numberOfTrailingZeros(remainingInk[seedRow]);
                } else if (seeds != 0) {
                    sx = wordColumn + Long.numberOfTrailingZeros(seeds) / Byte.SIZE;
                    seeds &= seeds - 1;
                } else if (scanColumn <= span - Long.BYTES) {
                    wordColumn = scanColumn;
                    seeds = darkSeedWord(gray, rasterRow + scanColumn);
                    if (seeds != 0 && visited != null) {
                        long seen = (long) SeedInkWords.WORDS.get(visited, seedBase + scanColumn);
                        seeds &= ~(seen << (Byte.SIZE - 1));
                    }
                    scanColumn += Long.BYTES;
                    continue;
                } else {
                    sx = scanColumn++;
                    if ((gray[rasterRow + sx] & 255) >= 170) continue;
                }
                int seed = seedBase + sx;
                if (visited != null && visited[seed] != 0) continue;
                if (queue == null) {
                    queue = scratch.queue(size);
                    if (!masked || span > Long.SIZE) {
                        scratch.reset(size);
                        visited = scratch.visited;
                    }
                    if (masked) {
                        if (scratch.crossingRows == null || scratch.crossingRows.length < rows * 2)
                            scratch.crossingRows = new int[rows * 2];
                        crossingRows = scratch.crossingRows;
                        if (span <= Long.SIZE) {
                            if (scratch.remainingInk == null || scratch.remainingInk.length < rows)
                                scratch.remainingInk = new long[rows];
                            remainingInk = scratch.remainingInk;
                            for (int row = 0; row < rows; row++) {
                                remainingInk[row] = line[lineOffset + row] ? 0
                                        : darkColumns(gray, rasterBase + row * width, span);
                            }
                        }
                        // Only the nearest visible row can be reached in each direction.
                        // Rebuild every visible entry before traversing the crop.
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
                if (remainingInk != null) remainingInk[seedRow] &= ~(1L << sx);
                else visited[seed] = 1;
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
                    if (crossingRows == null) {
                        int leftColumn = Math.max(0, x - 1), rightColumn = Math.min(span - 1, x + 1);
                        int firstRow = Math.max(0, row - 1), lastRow = Math.min(rows - 1, row + 1);
                        int nextBase = firstRow * span + leftColumn;
                        int raster = rasterBase + firstRow * width + leftColumn;
                        for (int neighbor = firstRow; neighbor <= lastRow;
                                neighbor++, nextBase += span, raster += width) {
                            int packed = neighbor << columnBits;
                            for (int column = leftColumn, next = nextBase, at = raster;
                                    column <= rightColumn; column++, next++, at++) {
                                if (visited[next] != 0) continue;
                                visited[next] = 1;
                                if ((gray[at] & 255) < 170) queue[count++] = packed | column;
                            }
                        }
                        continue;
                    }
                    for (int direction = -1; direction <= 1; direction++) {
                        int neighborRow = direction == 0 ? row
                                : crossingRows[row * 2 + (direction > 0 ? 1 : 0)];
                        if (neighborRow < 0 || neighborRow >= rows) continue;
                        int packedRow = neighborRow << columnBits;
                        int reach = direction == 0 ? 1 : (neighborRow - row) * direction;
                        int endColumn = Math.min(span - 1, x + reach);
                        int firstColumn = Math.max(0, x - reach);
                        if (remainingInk != null) {
                            long columns = (-1L << firstColumn) & (-1L >>> (Long.SIZE - 1 - endColumn));
                            long fresh = remainingInk[neighborRow] & columns;
                            remainingInk[neighborRow] &= ~columns;
                            while (fresh != 0) {
                                int col = Long.numberOfTrailingZeros(fresh);
                                fresh &= fresh - 1;
                                queue[count++] = packedRow | col;
                            }
                            continue;
                        }
                        int targetRow = neighborRow * span;
                        int targetRaster = rasterBase + neighborRow * width;
                        for (int xx = firstColumn, next = targetRow + firstColumn,
                                at = targetRaster + firstColumn; xx <= endColumn; xx++, next++, at++) {
                            if (visited[next] != 0) continue;
                            visited[next] = 1;
                            if ((gray[at] & 255) < 170) queue[count++] = packedRow | xx;
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
    private static long darkColumns(byte[] gray, int offset, int span) {
        long columns = 0;
        int x = 0;
        for (; x <= span - Long.BYTES; x += Long.BYTES) {
            long highBits = darkSeedWord(gray, offset + x);
            // Gather bit seven of each little-endian byte into one eight-column mask.
            long mask = ((highBits >>> 7) * 0x0102040810204080L) >>> 56;
            columns |= mask << x;
        }
        for (; x < span; x++)
            if ((gray[offset + x] & 255) < 170) columns |= 1L << x;
        return columns;
    }

    private static long darkSeedWord(byte[] gray, int offset) {
        long word = (long) SeedInkWords.WORDS.get(gray, offset);
        long bright = ((word | SeedInkWords.HIGH_BITS) - SeedInkWords.CUTOFF)
                & word & SeedInkWords.HIGH_BITS;
        return bright ^ SeedInkWords.HIGH_BITS;
    }

    private static final class SeedInkWords {
        static final java.lang.invoke.VarHandle WORDS = java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);
        static final long HIGH_BITS = 0x8080808080808080L;
        static final long CUTOFF = 0x0101010101010101L * (170 - 128);
    }

}
