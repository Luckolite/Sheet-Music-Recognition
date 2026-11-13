// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/** Recover a vertically touching chord only from a closed, shaded, lobed printed pocket. */
final class PaleChordHeadRecovery {
    private PaleChordHeadRecovery() {}

    record Head(
            int left,
            int top,
            int right,
            int bottom,
            float centerX,
            float centerY,
            float stemHeadY) {}

    static List<Head> find(
            byte[] labels, byte[] gray, int width, int height, float gap, int top, int bottom) {
        List<Head> result = new ArrayList<>();
        if (width < 1
                || height < 1
                || gray == null
                || labels == null
                || gray.length != (long) width * height
                || labels.length != gray.length
                || gap < 6
                || !Float.isFinite(gap)) return result;
        top = Math.max(0, top);
        bottom = Math.min(height - 1, bottom);
        if (bottom <= top) return result;
        float minWidth = gap * .55f, minHeight = gap * 1.45f;
        if (width - 2 < minWidth || bottom - top - 1 < minHeight) return result;
        int size = width * (bottom - top + 1), offset = top * width;
        int firstSeed = 0;
        while (firstSeed < size && (gray[offset + firstSeed] & 255) <= 150) firstSeed++;
        if (firstSeed == size) return result;
        float maxWidth = gap * 1.5f, maxHeight = gap * 3.85f;
        int profileHeight = Math.min(bottom - top + 1, (int) maxHeight);
        BitSet seen = new BitSet(size);
        long[] pending = new long[Math.min(size, 64)];
        int[] paperDistances = null;
        int paperAbove = Math.round(-gap * .2f), paperBelow = Math.round(gap * .2f);
        int firstStemDistance = Math.round(gap * .7f), lastStemDistance = stemEnd(gap);
        float lobeRadius = gap * .13f, neckRadius = gap * .10f;
        float minimumNeck = Math.max(.7f, gap * .045f);
        float headHalfHeight = gap * .45f;
        int[] histogram = null, rowLeft = null, rowRight = null;
        for (int seed = firstSeed; seed < size; seed++) {
            if (seen.get(seed)) {
                seed = seen.nextClearBit(seed) - 1;
                continue;
            }
            if ((gray[offset + seed] & 255) <= 150) continue;
            int firstRow = seed / width, seedBase = firstRow * width;
            // The first unseen pixel in raster order is on the highest row of its pocket.
            int upper = top + firstRow, lower = upper;
            int seedRowEnd = seedBase + width - 1;
            // A seed found in raster order already starts at the left end of its run.
            int startSeed = seed;
            pending[0] = takeRun(gray, seen, offset, seed, seed, seedRowEnd);
            seed = (int) pending[0];
            boolean isolated = seed == startSeed
                    && (seed >= size - width || (gray[offset + seed + width] & 255) <= 150);
            if (seed < seedRowEnd) seed++;
            if (isolated) continue;
            int left = width, right = 0, read = 0, endPending = 1, area = 0;
            boolean eligible = upper > top;
            while (read < endPending) {
                long run = pending[read++];
                int start = (int) (run >>> 32), end = (int) run;
                int row = start / width, base = row * width;
                int runLeft = start - base, runRight = end - base;
                if (eligible) {
                    left = Math.min(left, runLeft);
                    right = Math.max(right, runRight);
                    lower = Math.max(lower, top + row);
                    eligible = left > 0 && right < width - 1 && lower < bottom
                            && right - left + 1 <= maxWidth && lower - upper + 1 <= maxHeight;
                    if (eligible) area += end - start + 1;
                }
                // A closed pocket uses four-way connectivity. Only overlapping rows connect.
                for (int direction = -1; direction <= 1; direction += 2) {
                    int adjacent = base + direction * width;
                    if (adjacent < seedBase || adjacent >= size) continue;
                    int adjacentEnd = adjacent + width - 1;
                    int overlapEnd = adjacent + runRight;
                    for (int next = adjacent + runLeft; next <= overlapEnd; next++) {
                        if (seen.get(next)) {
                            next = seen.nextClearBit(next) - 1;
                            continue;
                        }
                        if ((gray[offset + next] & 255) <= 150) continue;
                        long found = takeRun(gray, seen, offset, next, adjacent, adjacentEnd);
                        if (endPending == pending.length) {
                            if (!eligible && read >= endPending / 2) {
                                endPending -= read;
                                System.arraycopy(pending, read, pending, 0, endPending);
                                read = 0;
                            } else {
                                pending = Arrays.copyOf(
                                        pending, endPending + Math.min(endPending, size - endPending));
                            }
                        }
                        pending[endPending++] = found;
                        next = (int) found;
                        // Extending the run already checked the next pixel within this row.
                        if (next < overlapEnd) next++;
                    }
                }
            }
            if (!eligible) continue;
            int w = right - left + 1, h = lower - upper + 1;
            if (w < minWidth || h < minHeight || area < w * h * .55f)
                continue;
            float heightInGaps = h / gap;
            int heads = Math.round(heightInGaps + .4f);
            if (heads < 2 || heads > 4 || Math.abs(heightInGaps - (heads - .4f)) > .32f)
                continue;
            if (rowLeft == null) {
                rowLeft = new int[profileHeight];
                rowRight = new int[profileHeight];
            }
            Arrays.fill(rowLeft, 0, h, width);
            Arrays.fill(rowRight, 0, h, 0);
            // Eligible components retain every run until the pocket proof finishes.
            for (int i = 0; i < endPending; i++) {
                long run = pending[i];
                int start = (int) (run >>> 32), end = (int) run;
                int row = start / width, base = row * width;
                int localRow = row - firstRow;
                rowLeft[localRow] = Math.min(rowLeft[localRow], start - base);
                rowRight[localRow] = Math.max(rowRight[localRow], end - base);
            }
            // Inter-head necks return inward relative to the neighbouring lobes.
            // Staff cells and long rectangular stem gaps cannot supply this outline.
            int proven = 0, requiredNecks = Math.max(1, heads - 2);
            for (int i = 0; i < heads - 1 && proven < requiredNecks; i++) {
                if (proven + heads - 1 - i < requiredNecks) break;
                float a = gap * (.4f + i), b = a + gap, neck = (a + b) * .5f;
                float la = average(rowLeft, h, a, lobeRadius), lb = average(rowLeft, h, b, lobeRadius);
                float ln = average(rowLeft, h, neck, neckRadius);
                if (ln - (la + lb) * .5f >= minimumNeck) {
                    proven++;
                    continue;
                }
                float ra = average(rowRight, h, a, lobeRadius), rb = average(rowRight, h, b, lobeRadius);
                float rn = average(rowRight, h, neck, neckRadius);
                if ((ra + rb) * .5f - rn >= minimumNeck) proven++;
            }
            // At most one neck may be buried under a staff/ledger crossing;
            // the complete closed pocket still fixes the number and spacing.
            if (proven < requiredNecks) continue;
            boolean up = stem(labels, gray, width, height, right + 2,
                    upper + gap * .4f, firstStemDistance, lastStemDistance, -1);
            if (!up && !stem(labels, gray, width, height, left - 2,
                    lower - gap * .2f, firstStemDistance, lastStemDistance, 1)) continue;
            if (histogram == null) histogram = new int[55];
            else Arrays.fill(histogram, 0);
            // A median above 205 cannot qualify. Only track the lower bins.
            for (int i = 0; i < endPending; i++) {
                long run = pending[i];
                int start = (int) (run >>> 32), end = (int) run;
                for (int at = offset + start, last = offset + end; at <= last; at++) {
                    int tone = gray[at] & 255;
                    if (tone <= 205) histogram[tone - 151]++;
                }
            }
            int rank = area / 2;
            int interior = 151;
            while (interior <= 205 && (rank -= histogram[interior - 151]) >= 0) interior++;
            if (interior > 205) continue;
            int count = 0, bright = 0, cx = (left + right) / 2, cy = (upper + lower) / 2;
            int minimumPaper = Math.max(190, interior + 22);
            if (paperDistances == null) {
                paperDistances = new int[3];
                for (int dx = 0; dx < paperDistances.length; dx++)
                    paperDistances[dx] = Math.round(gap * (1.3f + dx * .2f));
            }
            for (int dy = -1; dy <= 1; dy++) {
                int y = cy + (dy < 0 ? paperAbove : dy > 0 ? paperBelow : 0);
                if (y < 0 || y >= height) continue;
                int base = y * width;
                for (int distance : paperDistances) {
                    int low = cx - distance, high = cx + distance;
                    if (low >= 0 && low < width) {
                        count++;
                        if ((gray[base + low] & 255) >= minimumPaper) bright++;
                    }
                    if (high >= 0 && high < width) {
                        count++;
                        if ((gray[base + high] & 255) >= minimumPaper) bright++;
                    }
                }
            }
            if (count < 12 || bright < count - count * 3 / 4) continue;
            float stemHeadY = upper + gap * (.4f + (up ? 0 : heads - 1));
            float centerX = (left + right) * .5f;
            for (int i = 0; i < heads; i++) {
                float y = upper + gap * (.4f + i);
                result.add(
                        new Head(
                                left - 2,
                                Math.round(y - headHalfHeight),
                                right + 2,
                                Math.round(y + headHalfHeight),
                                centerX,
                                y,
                                stemHeadY));
            }
        }
        return result;
    }

    private static long takeRun(
            byte[] gray, BitSet seen, int offset, int seed, int rowStart, int rowEnd) {
        int left = seed, right = seed;
        while (left > rowStart && (gray[offset + left - 1] & 255) > 150) left--;
        while (right < rowEnd && (gray[offset + right + 1] & 255) > 150) right++;
        seen.set(left, right + 1);
        return ((long) left << 32) | right;
    }

    private static float average(int[] values, int length, float center, float radius) {
        int first = Math.max(0, Math.round(center - radius)),
                last = Math.min(length - 1, Math.round(center + radius));
        float sum = 0;
        for (int i = first; i <= last; i++) sum += values[i];
        return sum / Math.max(1, last - first + 1);
    }

    private static int stemEnd(float gap) {
        float limit = gap * 2.4f;
        int last = (int) limit;
        // Match the original integer-to-float comparison at large distances.
        while (last < Integer.MAX_VALUE && (float) (last + 1) <= limit) last++;
        return last;
    }

    private static boolean stem(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int edge,
            float cy,
            int first,
            int last,
            int direction) {
        int center = Math.round(cy);
        long startRow = center + (long) direction * first;
        long endRow = center + (long) direction * last;
        if (first > last || startRow < 0 || startRow >= height || endRow < 0 || endRow >= height)
            return false;
        int total = last - first + 1;
        int base = (int) startRow * width, step = direction * width;
        float requiredInk = total * .85f, requiredLabels = total * .5f;
        for (int x = Math.max(0, edge - 3); x <= Math.min(width - 1, edge + 3); x++) {
            int ink = 0, semantic = 0;
            for (int at = base + x, remaining = total; remaining > 0; remaining--, at += step) {
                if ((gray[at] & 255) < 150) ink++;
                if (labels[at] == OmrMeasurePostProcessor.STEM_OR_REST) semantic++;
            }
            if (ink >= requiredInk && semantic >= requiredLabels) return true;
        }
        return false;
    }
}
