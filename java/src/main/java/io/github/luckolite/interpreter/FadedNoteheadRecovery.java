// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/** Recovers pale filled heads whose dark outline survives but whose interior was unlabelled. */
final class FadedNoteheadRecovery {
    private static final int LOWER_THRESHOLD = 150;
    private static final int UPPER_THRESHOLD = 170;
    private static final int MAX_INTERIOR_TONE = 205;

    private FadedNoteheadRecovery() {}

    record Head(int left, int top, int right, int bottom) {}

    static List<Head> find(
            byte[] labels, byte[] gray, int width, int height, float gap, int top, int bottom) {
        List<Head> result = new ArrayList<>();
        if (width <= 0
                || height <= 0
                || gray == null
                || labels == null
                || gray.length != (long) width * height
                || labels.length != gray.length
                || gap < 6
                || !Float.isFinite(gap)) return result;
        top = Math.max(0, top);
        bottom = Math.min(height - 1, bottom);
        if (bottom <= top || width - 2 < gap * .55f || bottom - top - 1 < gap * .45f)
            return result;
        int stemEnd = (int) (gap * 2.3f);
        if (stemEnd >= height) return result;
        int size = width * (bottom - top + 1), offset = top * width;
        BitSet seen = new BitSet(size);
        int[] histogram = new int[MAX_INTERIOR_TONE - LOWER_THRESHOLD];
        long[] runs = scan(labels, gray, width, height, gap, top, bottom, LOWER_THRESHOLD,
                stemEnd, seen, new long[Math.min(size, 64)], histogram, result);
        // Only pixels admitted by the lower threshold can make the masks differ.
        if (hasThresholdDifference(gray, offset, seen)) {
            seen.clear();
            scan(labels, gray, width, height, gap, top, bottom, UPPER_THRESHOLD,
                    stemEnd, seen, runs, histogram, result);
        }
        return result;
    }

    private static boolean hasThresholdDifference(byte[] gray, int offset, BitSet seen) {
        int start = seen.nextSetBit(0);
        while (start >= 0) {
            int end = seen.nextClearBit(start);
            for (int at = offset + start, limit = offset + end; at < limit; at++) {
                if ((gray[at] & 255) <= UPPER_THRESHOLD) return true;
            }
            start = seen.nextSetBit(end);
        }
        return false;
    }

    private static long[] scan(
            byte[] labels, byte[] gray, int width, int height, float gap, int top, int bottom,
            int threshold, int stemEnd, BitSet seen, long[] runs, int[] histogram, List<Head> result) {
        int size = width * (bottom - top + 1), offset = top * width;
        boolean merge = threshold == UPPER_THRESHOLD;
        int firstBin = threshold - LOWER_THRESHOLD, lastRow = bottom - top;
        float maxWidth = gap * 1.5f, maxHeight = gap * 1.05f;
        float minWidth = gap * .55f, minHeight = gap * .45f;
        float duplicateWidth = gap * 1.6f, duplicateHeight = gap * 1.3f;
        int[] paperOffsets = {
            Math.round(gap * 1.3f),
            Math.round(gap * (1.3f + .2f)),
            Math.round(gap * (1.3f + 2 * .2f))
        };
        int[] paperRowOffsets = {Math.round(-gap * .2f), 0, Math.round(gap * .2f)};
        int stemStart = Math.round(gap * .65f);
        int seed = 0;
        while (seed < size) {
            while (seed < size && (gray[offset + seed] & 255) <= threshold) seed++;
            if (seed == size) break;
            if (seen.get(seed)) {
                seed = seen.nextClearBit(seed);
                continue;
            }
            int seedRow = seed / width, seedBase = seedRow * width;
            int seedRowEnd = seedBase + width - 1;
            runs[0] = takeRun(gray, seen, offset, seed, seed, seedRowEnd, threshold);
            int seedEnd = (int) runs[0];
            seed = seedEnd < seedRowEnd ? seedEnd + 2 : seedEnd + 1;
            int left = width, right = 0, lastComponentRow = seedRow;
            int read = 0, count = 1, area = 0;
            boolean eligible = seedRow > 0;
            while (read < count) {
                long run = runs[read++];
                int start = (int) (run >>> 32), end = (int) run;
                int row = start / width, base = row * width;
                if (eligible) {
                    left = Math.min(left, start - base);
                    right = Math.max(right, end - base);
                    lastComponentRow = Math.max(lastComponentRow, row);
                    eligible = left > 0 && right < width - 1 && lastComponentRow < lastRow
                            && right - left + 1 <= maxWidth
                            && lastComponentRow - seedRow + 1 <= maxHeight;
                    if (eligible) area += end - start + 1;
                }
                // Four-way connectivity only joins overlapping runs in neighboring rows.
                int firstDirection = row == seedRow ? 1 : -1;
                int lastDirection = row == lastRow ? -1 : 1;
                for (int direction = firstDirection; direction <= lastDirection; direction += 2) {
                    int stride = direction * width;
                    int adjacent = base + stride;
                    int overlapEnd = end + stride;
                    int next = start + stride;
                    while (next <= overlapEnd) {
                        while (next <= overlapEnd && (gray[offset + next] & 255) <= threshold) next++;
                        if (next > overlapEnd) break;
                        if (seen.get(next)) {
                            next = seen.nextClearBit(next);
                            continue;
                        }
                        long found = takeRun(
                                gray, seen, offset, next, adjacent, adjacent + width - 1, threshold);
                        if (count == runs.length) {
                            if (!eligible && read >= count / 2) {
                                count -= read;
                                System.arraycopy(runs, read, runs, 0, count);
                                read = 0;
                            } else {
                                runs = Arrays.copyOf(runs, count + Math.min(count, size - count));
                            }
                        }
                        runs[count++] = found;
                        int foundEnd = (int) found;
                        next = foundEnd < overlapEnd ? foundEnd + 2 : foundEnd + 1;
                    }
                }
            }
            if (!eligible) continue;
            float w = right - left + 1, h = lastComponentRow - seedRow + 1;
            if (w < minWidth || h < minHeight || area < w * h * .5f
                    || w / h < .65f || w / h > 2.1f) continue;
            int upper = top + seedRow, lower = top + lastComponentRow;
            int cy = (upper + lower) / 2;
            boolean downFits = stemEnd <= height - 1 - cy, upFits = stemEnd <= cy;
            if (!downFits && !upFits) continue;
            if (merge && nearbyHead(result, left - 2, upper - 2, right + 2, lower + 2,
                    duplicateWidth, duplicateHeight))
                continue;
            Arrays.fill(histogram, firstBin, histogram.length, 0);
            boolean rejected = false;
            int whiteAllowance = (area - 1) / 2;
            evidence: for (int i = 0; i < count; i++) {
                long run = runs[i];
                for (int at = offset + (int) (run >>> 32), last = offset + (int) run;
                        at <= last; at++) {
                    if (labels[at] == OmrMeasurePostProcessor.NOTEHEAD) {
                        rejected = true;
                        break evidence;
                    }
                    int tone = gray[at] & 255;
                    if (tone <= MAX_INTERIOR_TONE) histogram[tone - LOWER_THRESHOLD - 1]++;
                    else if (--whiteAllowance < 0) {
                        // Too many bright pixels for the upper median to qualify.
                        rejected = true;
                        break evidence;
                    }
                }
            }
            if (rejected) continue;
            int rank = area / 2, medianBin = firstBin;
            // The bright-pixel allowance guarantees the upper median lies in the histogram.
            while ((rank -= histogram[medianBin]) >= 0) medianBin++;
            int interior = LOWER_THRESHOLD + 1 + medianBin;
            int cx = (left + right) / 2;
            int paperMinimum = Math.max(190, interior + 22);
            int paperCount = 0, paperBelow = 0;
            int maxPaperBelow = paperRowOffsets.length * 2 * paperOffsets.length * 3 / 4;
            paper:
            for (int dy : paperRowOffsets) {
                int y = cy + dy;
                if (y < 0 || y >= height) continue;
                int base = y * width;
                for (int side = -1; side <= 1; side += 2) {
                    for (int dx : paperOffsets) {
                        int x = cx + side * dx;
                        if (x < 0 || x >= width) continue;
                        paperCount++;
                        if ((gray[base + x] & 255) < paperMinimum && ++paperBelow > maxPaperBelow)
                            break paper;
                    }
                }
            }
            // The upper quartile passes exactly when at most its index falls below the cutoff.
            if (paperCount < 12 || paperBelow > paperCount * 3 / 4) continue;
            int edge = Math.round(h * .12f);
            int topProbe = seedRow + edge, bottomProbe = lastComponentRow - edge;
            int middleProbe = seedRow + ((int) h - 1) / 2;
            int topLeft = width, middleLeft = width, bottomLeft = width;
            int topRight = 0, middleRight = 0, bottomRight = 0;
            for (int i = 0; i < count; i++) {
                long run = runs[i];
                int start = (int) (run >>> 32), row = start / width;
                if (row != topProbe && row != middleProbe && row != bottomProbe) continue;
                int base = row * width, runLeft = start - base, runRight = (int) run - base;
                if (row == topProbe) {
                    topLeft = Math.min(topLeft, runLeft);
                    topRight = Math.max(topRight, runRight);
                }
                if (row == middleProbe) {
                    middleLeft = Math.min(middleLeft, runLeft);
                    middleRight = Math.max(middleRight, runRight);
                }
                if (row == bottomProbe) {
                    bottomLeft = Math.min(bottomLeft, runLeft);
                    bottomRight = Math.max(bottomRight, runRight);
                }
            }
            float bow = Math.max((topLeft + bottomLeft) * .5f - middleLeft,
                    middleRight - (topRight + bottomRight) * .5f);
            // Staff rules can clip an oval's curved cap down to half a pixel.
            // A perfectly rectangular cell still has no returning side; require
            // the closed contrasted pocket and independently printed/model stem.
            if (bow < Math.max(.5f, gap * .025f)) continue;
            if (!(downFits && stem(labels, gray, width, left - 2, cy, stemStart, stemEnd, 1))
                    && !(upFits && stem(labels, gray, width, right + 2, cy, stemStart, stemEnd, -1)))
                continue;
            if (flagPocketOnOwnedStem(labels, gray, width, height, left, upper, right, lower, gap))
                continue;
            result.add(new Head(left - 2, upper - 2, right + 2, lower + 2));
        }
        return runs;
    }

    private static boolean nearbyHead(
            List<Head> heads, int left, int top, int right, int bottom,
            float duplicateWidth, float duplicateHeight) {
        for (Head prior : heads) {
            if (Math.abs(prior.left() + prior.right() - left - right) < duplicateWidth
                    && Math.abs(prior.top() + prior.bottom() - top - bottom) < duplicateHeight)
                return true;
        }
        return false;
    }

    private static long takeRun(byte[] gray, BitSet seen, int offset, int seed,
            int rowStart, int rowEnd, int threshold) {
        int left = seed, right = seed;
        while (left > rowStart && (gray[offset + left - 1] & 255) > threshold) left--;
        while (right < rowEnd && (gray[offset + right + 1] & 255) > threshold) right++;
        seen.set(left, right + 1);
        return ((long) left << 32) | right;
    }

    /** A compact flag pocket is opposite the already recognized head on the same shaft. */
    static boolean flagPocketOnOwnedStem(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            float gap) {
        return flagPocketOnOwnedStem(
                labels, gray, width, height, left, top, right, bottom, gap, null);
    }

    @FunctionalInterface
    interface StemOwner {
        boolean owns(int stemX, int headY, int direction);
    }

    /** Semantic seeds additionally require the recognized owner to use this exact shaft. */
    static boolean flagPocketOnOwnedStem(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            float gap,
            StemOwner owner) {
        if (labels == null
                || gray == null
                || width < 1
                || height < 1
                || labels.length != (long) width * height
                || gray.length != labels.length
                || !Float.isFinite(gap)
                || gap < 6
                || left < 0
                || top < 0
                || right >= width
                || bottom >= height
                || right < left
                || bottom < top
                || right - left + 1 > gap * .85f
                || bottom - top + 1 > gap * .85f) return false;
        int cy = (top + bottom) / 2;
        for (int direction : new int[] {-1, 1}) {
            int edge = direction > 0 ? left - 2 : right + 2;
            for (int sx = Math.max(0, edge - 3); sx <= Math.min(width - 1, edge + 3); sx++) {
                int ink = 0, semantic = 0, rows = 0, headRows = 0;
                for (int distance = 0; distance <= gap * 3.5f; distance++) {
                    int y = cy + direction * distance;
                    if (y < 0 || y >= height) break;
                    rows++;
                    if ((gray[y * width + sx] & 255) < 175) ink++;
                    if (labels[y * width + sx] == OmrMeasurePostProcessor.STEM_OR_REST) semantic++;
                    if (distance < gap * 1.25f) continue;
                    int headInk = 0;
                    for (int dx = Math.max(2, Math.round(gap * .25f)); dx <= gap * 1.5f; dx++) {
                        int x = sx - direction * dx;
                        if (x >= 0
                                && x < width
                                && labels[y * width + x] == OmrMeasurePostProcessor.NOTEHEAD
                                && (gray[y * width + x] & 255) < 175) headInk++;
                    }
                    headRows = headInk >= gap * .5f ? headRows + 1 : 0;
                    if (headRows >= 3
                            && ink >= rows * .85f
                            && semantic >= rows * .5f
                            && (owner == null || owner.owns(sx, y, direction))) return true;
                }
            }
        }
        return false;
    }

    private static boolean stem(
            byte[] labels,
            byte[] gray,
            int width,
            int edge,
            int cy,
            int start,
            int end,
            int direction) {
        int total = end - start + 1, stride = direction * width;
        int firstRow = (cy + direction * start) * width;
        float requiredInk = total * .85f, requiredSemantic = total * .5f;
        for (int x = Math.max(0, edge - 3); x <= Math.min(width - 1, edge + 3); x++) {
            int ink = 0, semantic = 0, at = firstRow + x;
            for (int remaining = total - 1; remaining >= 0; remaining--, at += stride) {
                if ((gray[at] & 255) < LOWER_THRESHOLD) ink++;
                if (labels[at] == OmrMeasurePostProcessor.STEM_OR_REST) semantic++;
                if (ink >= requiredInk && semantic >= requiredSemantic) return true;
                if (ink + remaining < requiredInk || semantic + remaining < requiredSemantic) break;
            }
        }
        return false;
    }
}
