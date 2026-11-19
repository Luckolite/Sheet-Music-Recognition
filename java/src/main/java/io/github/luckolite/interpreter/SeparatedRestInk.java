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

    private static final int EMPTY_CROP_CACHE_SIZE = 1024;

    private record EmptyCrop(long bounds, long ignoredRows, int gapBits) {}

    static final class Scratch {
        private final byte[] readRaster;
        private final int readWidth, readHeight;
        private EmptyCrop[] emptyCrops;

        Scratch() {
            this(null, 0, 0);
        }

        /** Scope empty results to one scan of an unchanged raster. */
        Scratch(byte[] readRaster, int readWidth, int readHeight) {
            this.readRaster = readRaster;
            this.readWidth = readWidth;
            this.readHeight = readHeight;
        }

        private byte[] visited = new byte[0];
        private int[] queue = new int[0];
        private int[] crossingRows;
        private long[] remainingInk;
        private long[] pending;

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
        if (scratch.readRaster != gray || gray == null || scratch.readWidth != width
                || scratch.readHeight != height || line == null || line.length > Long.SIZE
                || left < 0 || right < left || top < 0 || bottom < top
                || (left | right | top | bottom) > 65535 || line.length != bottom - top + 1)
            return findUncached(gray, width, height, left, right, top, bottom, line, gap, scratch);

        long bounds = ((long) left << 48) | ((long) right << 32) | ((long) top << 16) | bottom;
        long ignoredRows = 0L;
        for (int i = 0; i < line.length; i++)
            if (line[i]) ignoredRows |= 1L << i;
        int gapBits = Float.floatToRawIntBits(gap);
        int slot = (Long.hashCode(bounds) * 31 + Long.hashCode(ignoredRows) * 17 + gapBits)
                & (EMPTY_CROP_CACHE_SIZE - 1);
        var emptyCrops = scratch.emptyCrops;
        if (emptyCrops != null) {
            var previous = emptyCrops[slot];
            if (previous != null && previous.bounds() == bounds && previous.ignoredRows() == ignoredRows
                    && previous.gapBits() == gapBits) return List.of();
        }

        var result = findUncached(gray, width, height, left, right, top, bottom, line, gap, scratch);
        if (result.isEmpty()) {
            if (emptyCrops == null) scratch.emptyCrops = emptyCrops = new EmptyCrop[EMPTY_CROP_CACHE_SIZE];
            emptyCrops[slot] = new EmptyCrop(bounds, ignoredRows, gapBits);
        }
        return result;
    }

    private static List<Body> findUncached(
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
        int rowWords = (span - 1) / Long.SIZE + 1;
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
            int activeWord = 0;
            while (remainingInk != null || seeds != 0 || scanColumn < span) {
                int sx;
                if (remainingInk != null && rowWords == 1) {
                    long available = remainingInk[seedRow];
                    if (available == 0) break;
                    sx = Long.numberOfTrailingZeros(available);
                } else if (remainingInk != null) {
                    int rowStart = seedRow * rowWords;
                    while (activeWord < rowWords && remainingInk[rowStart + activeWord] == 0) activeWord++;
                    if (activeWord == rowWords) break;
                    sx = activeWord * Long.SIZE + Long.numberOfTrailingZeros(remainingInk[rowStart + activeWord]);
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
                    if (!masked) {
                        scratch.reset(size);
                        visited = scratch.visited;
                    }
                    if (masked) {
                        if (scratch.crossingRows == null || scratch.crossingRows.length < rows * 2)
                            scratch.crossingRows = new int[rows * 2];
                        crossingRows = scratch.crossingRows;
                        int wordCount = rows * rowWords;
                        if (scratch.remainingInk == null || scratch.remainingInk.length < wordCount)
                            scratch.remainingInk = new long[wordCount];
                        remainingInk = scratch.remainingInk;
                        for (int row = 0; row < rows; row++) {
                            if (!line[lineOffset + row])
                                PackedGrayInk.fill(gray, rasterBase + row * width, span, 170,
                                        remainingInk, row * rowWords);
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
                if (remainingInk != null) remainingInk[seedRow * rowWords + sx / Long.SIZE] &= ~(1L << sx);
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
                    if (!eligible && crossingRows != null && rowWords <= 2) {
                        if (rowWords == 1) {
                            discardComponent(queue, cursor - 1, count, columnBits, columnMask,
                                    rows, crossingRows, remainingInk, scratch);
                        } else {
                            discardWideComponent(queue, cursor - 1, count, columnBits, columnMask,
                                    rows, crossingRows, remainingInk, scratch);
                        }
                        break;
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
                    if (rowWords == 1) {
                        int previous = crossingRows[row * 2], next = crossingRows[row * 2 + 1];
                        if (previous >= 0) count = appendNeighbors(
                                previous, x, row - previous, columnBits, remainingInk, queue, count);
                        count = appendNeighbors(row, x, 1, columnBits, remainingInk, queue, count);
                        if (next >= 0) count = appendNeighbors(
                                next, x, next - row, columnBits, remainingInk, queue, count);
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
                        int firstWord = firstColumn >>> 6, lastWord = endColumn >>> 6;
                        long columns = -1L << firstColumn;
                        int rowStart = neighborRow * rowWords;
                        for (int word = firstWord; word <= lastWord; word++) {
                            if (word == lastWord) columns &= -1L >>> (63 - (endColumn & 63));
                            int at = rowStart + word;
                            long fresh = remainingInk[at] & columns;
                            remainingInk[at] &= ~columns;
                            int packedBase = packedRow | (word << 6);
                            while (fresh != 0) {
                                queue[count++] = packedBase | Long.numberOfTrailingZeros(fresh);
                                fresh &= fresh - 1;
                            }
                            // Subsequent words start at column zero.
                            columns = -1L;
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

    private static int appendNeighbors(int row, int x, int reach, int columnBits, long[] ink, int[] queue, int count) {
        // The packed row has no set bits beyond the crop's right edge.
        long mask = (-1L << Math.max(0, x - reach)) & (-1L >>> (63 - Math.min(63, x + reach)));
        long fresh = ink[row] & mask;
        if (fresh != 0) {
            ink[row] ^= fresh;
            int base = row << columnBits;
            do {
                queue[count++] = base | Long.numberOfTrailingZeros(fresh);
                fresh &= fresh - 1;
            } while (fresh != 0);
        }
        return count;
    }

    /** Once the bounds rule fails, only the set of reachable pixels matters. */
    private static void discardComponent(int[] work, int first, int count, int columnBits,
            int columnMask, int rows, int[] crossing, long[] ink, Scratch scratch) {
        if (scratch.pending == null || scratch.pending.length < rows) scratch.pending = new long[rows];
        long[] pending = scratch.pending;
        // Compact rows into the consumed queue prefix. Each input adds at most one row.
        int active = 0;
        for (int i = first; i < count; i++) {
            int pixel = work[i], row = pixel >>> columnBits;
            if (pending[row] == 0) work[active++] = row;
            pending[row] |= 1L << (pixel & columnMask);
        }
        while (active > 0) {
            int row = work[--active];
            long run = pending[row], wave = run;
            pending[row] = 0;
            while ((wave = ((wave << 1) | (wave >>> 1)) & ink[row]) != 0) {
                ink[row] &= ~wave;
                run |= wave;
            }
            for (int dir = 0; dir < 2; dir++) {
                int neighbor = crossing[row * 2 + dir];
                if (neighbor < 0 || ink[neighbor] == 0) continue;
                long columns = run;
                int radius = Math.min(63, Math.abs(neighbor - row));
                for (int covered = 0; covered < radius;) {
                    int step = Math.min(radius - covered, 2 * covered + 1);
                    columns |= (columns << step) | (columns >>> step);
                    covered += step;
                }
                long fresh = columns & ink[neighbor];
                if (fresh == 0) continue;
                ink[neighbor] &= ~fresh;
                if (pending[neighbor] == 0) work[active++] = neighbor;
                pending[neighbor] |= fresh;
            }
        }
    }

    /** Finish discarding a component whose visible rows use two words. */
    private static void discardWideComponent(int[] work, int first, int count, int columnBits,
            int columnMask, int rows, int[] crossing, long[] ink, Scratch scratch) {
        if (scratch.pending == null || scratch.pending.length < rows * 2)
            scratch.pending = new long[rows * 2];
        long[] pending = scratch.pending;
        int active = 0;
        for (int i = first; i < count; i++) {
            int pixel = work[i], row = pixel >>> columnBits, base = row * 2;
            if ((pending[base] | pending[base + 1]) == 0) work[active++] = row;
            int x = pixel & columnMask;
            pending[base + (x >>> 6)] |= 1L << x;
        }
        while (active > 0) {
            int row = work[--active], base = row * 2;
            long waveLow = pending[base], waveHigh = pending[base + 1];
            long low = waveLow, high = waveHigh;
            pending[base] = pending[base + 1] = 0;
            for (;;) {
                long nextLow = ((waveLow << 1) | (waveLow >>> 1) | (waveHigh << 63)) & ink[base];
                long nextHigh = ((waveHigh << 1) | (waveHigh >>> 1) | (waveLow >>> 63)) & ink[base + 1];
                if ((nextLow | nextHigh) == 0) break;
                ink[base] ^= nextLow;
                ink[base + 1] ^= nextHigh;
                low |= nextLow;
                high |= nextHigh;
                waveLow = nextLow;
                waveHigh = nextHigh;
            }
            for (int direction = 0; direction < 2; direction++) {
                int neighbor = crossing[base + direction];
                if (neighbor < 0) continue;
                int nextBase = neighbor * 2;
                if ((ink[nextBase] | ink[nextBase + 1]) == 0) continue;
                long columnsLow = low, columnsHigh = high;
                int reach = Math.min(127, Math.abs(neighbor - row));
                for (int covered = 0; covered < reach;) {
                    int step = Math.min(reach - covered, Math.min(63, 2 * covered + 1));
                    long nextLow = columnsLow | (columnsLow << step) | (columnsLow >>> step)
                            | (columnsHigh << (64 - step));
                    columnsHigh |= (columnsHigh << step) | (columnsHigh >>> step)
                            | (columnsLow >>> (64 - step));
                    columnsLow = nextLow;
                    covered += step;
                }
                long freshLow = columnsLow & ink[nextBase], freshHigh = columnsHigh & ink[nextBase + 1];
                if ((freshLow | freshHigh) == 0) continue;
                ink[nextBase] ^= freshLow;
                ink[nextBase + 1] ^= freshHigh;
                if ((pending[nextBase] | pending[nextBase + 1]) == 0) work[active++] = neighbor;
                pending[nextBase] |= freshLow;
                pending[nextBase + 1] |= freshHigh;
            }
        }
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
