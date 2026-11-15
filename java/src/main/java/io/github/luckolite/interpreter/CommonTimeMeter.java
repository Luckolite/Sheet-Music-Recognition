// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Raw, open-right C signs following a printed clef. Does not infer a tempo or beat unit. */
final class CommonTimeMeter {
    record Reading(MeterChangeDetector.Crop crop, int numerator, int denominator) {}

    static List<Reading> candidates(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int firstLine,
            float gap,
            float slope) {
        if (labels == null
                || gray == null
                || labels.length != width * height
                || gray.length != width * height
                || gap < 6
                || !Float.isFinite(gap)) return List.of();
        var found = new ArrayList<Reading>();
        int middle = Math.round(firstLine + 2 * gap);
        int radius = Math.max(1, Math.round(gap * .12f));
        int[] columns = new int[width];
        int[] shifts = null;
        if (slope != 0) {
            shifts = new int[width];
            for (int x = 0; x < width; x++) shifts[x] = Math.round(slope * (x - width * .5f));
        }
        int firstRow = Math.round(middle - 1.65f * gap), lastRow = Math.round(middle + 1.65f * gap);
        if (shifts == null) {
            for (int y = Math.max(0, firstRow); y <= Math.min(height - 1, lastRow); y++) {
                if (rule(y, firstLine, gap, radius)) continue;
                for (int x = 0, at = y * width; x < width; x++, at++)
                    if ((gray[at] & 255) < 155) columns[x]++;
            }
        } else {
            for (int y = firstRow; y <= lastRow; y++) {
                if (rule(y, firstLine, gap, radius)) continue;
                for (int x = 0; x < width; x++) {
                    int yy = y + shifts[x];
                    if (yy >= 0 && yy < height && (gray[yy * width + x] & 255) < 155)
                        columns[x]++;
                }
            }
        }
        int maxBlank = Math.max(1, Math.round(gap * .20f));
        for (int x = 0; x < width; x++) {
            if (columns[x] < 2) continue;
            int left = x, last = x, blanks = 0;
            for (; x < width; x++) {
                if (columns[x] >= 2) {
                    last = x;
                    blanks = 0;
                } else if (++blanks > maxBlank) break;
            }
            int span = last - left + 1;
            if (span < gap * .9f
                    || span > gap * 2.7f
                    || !precedingClef(labels, width, height, left, firstLine, gap, shifts)) continue;
            int meter = read(gray, width, height, left, last, firstLine, gap, slope);
            if (meter == 0) continue;
            int localTop = firstLine + Math.round(slope * ((left + last) * .5f - width * .5f));
            int pad = Math.max(2, Math.round(gap * .2f));
            found.add(
                    new Reading(
                            new MeterChangeDetector.Crop(
                                    Math.max(0, left - pad),
                                    Math.max(0, localTop - pad),
                                    Math.min(width, last + pad + 1),
                                    Math.min(height, Math.round(localTop + gap * 4) + pad + 1),
                                    localTop,
                                    gap),
                            meter,
                            meter));
        }
        return List.copyOf(found);
    }

    /** Read a bounded system header when ink joins a sign to neighboring components. */
    static int readHeader(
            byte[] labels, byte[] gray, int width, int height, int header, int top, float gap) {
        int start = Math.max(0, header - Math.round(gap * 2));
        int limit = Math.min(width - 1, header + Math.round(gap * 4));
        int glyph = -1, meter = 0;
        for (int left = start; left < limit; left++) {
            if (!precedingClef(labels, width, height, left, top, gap, null)) continue;
            int min = left + Math.round(gap * .9f);
            int max = Math.min(limit, left + Math.round(gap * 2.5f));
            for (int right = min; right <= max; right++) {
                int reading = read(gray, width, height, left, right, top, gap, 0);
                if (reading == 0) continue;
                int center = (left + right) / 2;
                if (meter != 0 && (meter != reading || Math.abs(center - glyph) > gap * .55f))
                    return 0;
                if (meter == 0) glyph = center;
                meter = reading;
            }
        }
        return meter;
    }
    /** 4 means C, 2 means C with a central continuous cut; zero is not proven. */
    static int read(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int firstLine,
            float gap,
            float slope) {
        if (gray == null
                || gray.length != width * height
                || gap < 6
                || right <= left
                || left < 0
                || right >= width) return 0;
        if (sharpInk(gray, width, height, left, right, firstLine, gap, slope)) return 0;
        float mid = firstLine + 2 * gap;
        int span = right - left + 1;
        int radius = Math.max(1, Math.round(gap * .12f));
        int top = height, bottom = -1, heads = 0;
        for (int y = Math.round(mid - gap * 1.65f); y <= Math.round(mid + gap * 1.65f); y++) {
            if (rule(y, firstLine, gap, radius)) continue;
            for (int x = left; x <= right; x++)
                if (ink(gray, width, height, x, y, slope)) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                    heads++;
                }
        }
        if (top > mid - gap * .75f
                || bottom < mid + gap * .75f
                || bottom - top > gap * 3.25f
                || heads < gap * gap * .35f) return 0;
        // The right opening must be genuinely empty; a whole note or 0 is closed here.
        int open = 0, total = 0;
        for (int y = Math.round(mid - gap * .30f); y <= Math.round(mid + gap * .30f); y++) {
            if (rule(y, firstLine, gap, radius)) continue;
            for (int x = Math.round(left + span * .72f); x <= right; x++) {
                total++;
                if (ink(gray, width, height, x, y, slope)) open++;
            }
        }
        if (total == 0
                || open > total * .16f
                        && !openChannel(
                                gray, width, height, left, right, mid, gap, firstLine, slope))
            return 0;
        // Both right terminals and both left shoulders must be present, not a 3 or rest.
        // Pixel counts are integral: retain the whole-pixel portion of the scaled minimum.
        if (count(
                                gray,
                                width,
                                height,
                                left + span * .55f,
                                right,
                                mid - gap * 1.35f,
                                mid - gap * .35f,
                                firstLine,
                                gap,
                                slope)
                        < Math.floor(gap * .65f)
                || count(
                                gray,
                                width,
                                height,
                                left + span * .55f,
                                right,
                                mid + gap * .35f,
                                mid + gap * 1.35f,
                                firstLine,
                                gap,
                                slope)
                        < Math.floor(gap * .65f)
                || count(
                                gray,
                                width,
                                height,
                                left,
                                left + span * .35f,
                                mid - gap * .75f,
                                mid - gap * .18f,
                                firstLine,
                                gap,
                                slope)
                        < Math.floor(gap * .65f)
                || count(
                                gray,
                                width,
                                height,
                                left,
                                left + span * .35f,
                                mid + gap * .18f,
                                mid + gap * .75f,
                                firstLine,
                                gap,
                                slope)
                        < Math.floor(gap * .65f)) return 0;
        // The C's left arc crosses the middle while its interior remains open.
        if (count(
                        gray,
                        width,
                        height,
                        left,
                        left + span * .35f,
                        mid - gap * .30f,
                        mid + gap * .30f,
                        firstLine,
                        gap,
                        slope)
                < gap * .35f) return 0;
        boolean cut = false;
        for (int x = Math.round(left + span * .32f); x <= Math.round(left + span * .68f); x++) {
            int present = 0, rows = 0;
            for (int y = Math.round(mid - gap * 1.15f); y <= Math.round(mid + gap * 1.15f); y++) {
                if (rule(y, firstLine, gap, radius)) continue;
                rows++;
                if (ink(gray, width, height, x, y, slope)) present++;
            }
            if (rows > 0
                    && present >= rows * .90f
                    && separatedCut(gray, width, height, left, x, mid, gap, slope, firstLine)) {
                cut = true;
                break;
            }
        }
        // A bold solid patch cannot be a C; allow only a narrow cut in the interior.
        int cavity =
                count(
                        gray,
                        width,
                        height,
                        left + span * .36f,
                        left + span * .70f,
                        mid - gap * .55f,
                        mid + gap * .55f,
                        firstLine,
                        gap,
                        slope);
        if (cavity > gap * gap * (cut ? .30f : .09f)) return 0;
        return cut ? 2 : 4;
    }

    /** Two long shafts crossed by two overhanging bars belong to a sharp, not a cut-C. */
    private static boolean sharpInk(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int firstLine,
            float gap,
            float slope) {
        int span = right - left + 1;
        if (span < gap * .65f || span > gap * 1.75f) return false;
        int radius = Math.max(1, Math.round(gap * .12f));
        int lo = Math.round(firstLine - gap), hi = Math.round(firstLine + gap * 5);
        int slant = Math.round(gap * .65f);
        int maskTop = Math.min(lo, height) - slant - 1;
        int maskBottom = hi + slant + 1;
        boolean[] staffRows = new boolean[maskBottom - maskTop + 1];
        int[] clearRows = new int[staffRows.length + 1];
        for (int row = 0; row < staffRows.length; row++) {
            staffRows[row] = sharpStaffRule(
                    gray, width, height, left, right, row + maskTop, firstLine, gap, radius, slope);
            clearRows[row + 1] = clearRows[row] + (staffRows[row] ? 0 : 1);
        }
        int[] tops = new int[span], bottoms = new int[span];
        java.util.Arrays.fill(tops, height);
        java.util.Arrays.fill(bottoms, -1);
        int[] deskew = new int[span];
        for (int x = left; x <= right; x++) {
            int shift = Math.round(slope * (x - width * .5f));
            deskew[x - left] = shift;
            int hits = 0;
            for (int y = lo; y <= hi; y++) {
                if (staffRows[y - maskTop] || !inkAt(gray, width, height, x, y + shift)) continue;
                tops[x - left] = Math.min(tops[x - left], y);
                bottoms[x - left] = Math.max(bottoms[x - left], y);
                hits++;
            }
            int top = tops[x - left], bottom = bottoms[x - left];
            if (bottom < 0 || bottom - top < gap * 2.1f
                    || hits < (clearRows[bottom - maskTop + 1] - clearRows[top - maskTop]) * .85f) {
                tops[x - left] = height;
                bottoms[x - left] = -1;
            }
        }
        int wing = Math.max(2, Math.round(gap * .12f));
        int join = Math.max(2, (int) Math.ceil(gap * .4f));
        float[] centers = null;
        int slantCount = 2 * slant + 1;
        long offsetCells = (long) slantCount * span;
        long fitCells = (long) slantCount * staffRows.length;
        int[] rowOffsets = null;
        long[] fits = null;
        for (int a = left + wing; a <= right - wing; a++) {
            if (bottoms[a - left] < 0) continue;
            for (int b = a + Math.max(2, Math.round(gap * .25f));
                    b <= right - wing && b - a <= gap * .85f;
                    b++) {
                if (bottoms[b - left] < 0
                        || Math.abs(tops[a - left] - tops[b - left]) > gap * .45f
                        || Math.abs(bottoms[a - left] - bottoms[b - left]) > gap * .45f) continue;
                int top = Math.max(tops[a - left], tops[b - left]);
                int bottom = Math.min(bottoms[a - left], bottoms[b - left]);
                if (bottom - top < gap * 1.9f) continue;
                if (rowOffsets == null && offsetCells <= 65_536) {
                    rowOffsets = new int[(int) offsetCells];
                    for (int angle = 0; angle < slantCount; angle++) {
                        int dy = angle - slant;
                        for (int column = 0; column < span; column++)
                            rowOffsets[angle * span + column] =
                                    Math.round(dy * column / (float) (span - 1));
                    }
                }
                if (fits == null && fitCells <= 65_536) fits = new long[(int) fitCells];
                int bandStart = top, bandEnd = top - 1, n = 0;
                for (int y = top; y <= bottom; y++) {
                    boolean crossed = false;
                    for (int angle = 0; angle < slantCount; angle++) {
                        int fitIndex = fits == null ? 0 : (y - maskTop) * slantCount + angle;
                        long edges = fits == null ? 0 : fits[fitIndex];
                        if (edges == 0) {
                            int dy = angle - slant;
                            int offset = rowOffsets == null ? 0 : angle * span;
                            int hits = 0, total = 0, firstDark = -1, lastDark = -1;
                            for (int column = 0; column < span; column++) {
                                int rowOffset = rowOffsets == null
                                        ? Math.round(dy * column / (float) (span - 1))
                                        : rowOffsets[offset + column];
                                int yy = y + rowOffset;
                                int row = yy - maskTop;
                                if (staffRows[row]) continue;
                                total++;
                                int x = left + column;
                                int physicalY = yy + deskew[column];
                                boolean dark = inkAt(gray, width, height, x, physicalY)
                                        || !staffRows[row - 1]
                                                && inkAt(gray, width, height, x, physicalY - 1)
                                        || !staffRows[row + 1]
                                                && inkAt(gray, width, height, x, physicalY + 1);
                                if (dark) {
                                    if (hits++ == 0) firstDark = x;
                                    lastDark = x;
                                }
                            }
                            // A fit is independent of the spine pair until its two wings are checked.
                            edges = total >= Math.max(6, span * .35f) && hits >= total * .86f
                                    ? (((long) firstDark + 1) << 32) | lastDark
                                    : -1;
                            if (fits != null) fits[fitIndex] = edges;
                        }
                        if (edges > 0 && (edges >>> 32) - 1 < a && (int) edges > b) {
                            crossed = true;
                            break;
                        }
                    }
                    if (!crossed) continue;
                    // A nearby fit belongs to the same band only if every intervening row was masked.
                    if (bandEnd >= bandStart && y - bandEnd <= join
                            && clearRows[y - maskTop] == clearRows[bandEnd - maskTop + 1]) {
                        bandEnd = y;
                        continue;
                    }
                    if (bandEnd >= bandStart) {
                        float center = (bandStart + bandEnd) * .5f;
                        if (matchesBandCenter(centers, n, center, gap)) return true;
                        if (centers == null) centers = new float[hi - Math.min(lo, height) + 1];
                        centers[n++] = center;
                    }
                    bandStart = y;
                    bandEnd = y;
                }
                if (bandEnd >= bandStart
                        && matchesBandCenter(centers, n, (bandStart + bandEnd) * .5f, gap)) return true;
            }
        }
        return false;
    }

    private static boolean inkAt(byte[] gray, int width, int height, int x, int y) {
        return y >= 0 && y < height && (gray[y * width + x] & 255) < 155;
    }

    private static boolean matchesBandCenter(float[] centers, int count, float center, float gap) {
        for (int i = 0; i < count; i++) {
            float separation = center - centers[i];
            // Both fitted centers can shift by one antialiased edge pixel.
            if (separation >= gap * .5f && separation <= gap * 1.4f + 2) return true;
        }
        return false;
    }

    /** Remove only staff ink witnessed outside the accidental, retaining its crossbars. */
    private static boolean sharpStaffRule(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int y,
            int firstLine,
            float gap,
            int radius,
            float slope) {
        if (!rule(y, firstLine, gap, radius)) return false;
        int a = Math.round(left - gap), b = Math.round(right + gap);
        if (a < 0 || b >= width) return true;
        return ink(gray, width, height, a, y, slope) && ink(gray, width, height, b, y, slope);
    }

    /** A cut-C has a separate left bowl, white channel, and vertical cut in both halves.
     * A quarter rest's zig-zag spine and lower hook cannot provide that topology. */
    private static boolean separatedCut(
            byte[] gray,
            int width,
            int height,
            int left,
            int cut,
            float mid,
            float gap,
            float slope,
            int firstLine) {
        int[] supported = new int[2], total = new int[2];
        int radius = Math.max(1, Math.round(gap * .12f));
        int channelEnd = cut - Math.max(1, Math.round(gap * .10f));
        for (int y = Math.round(mid - gap * .7f); y <= Math.round(mid + gap * .7f); y++) {
            if (rule(y, firstLine, gap, radius)) continue;
            int half = y < mid ? 0 : 1;
            total[half]++;
            if (!ink(gray, width, height, cut, y, slope)) continue;
            boolean bowl = false;
            for (int x = left; x < channelEnd; x++) {
                if (ink(gray, width, height, x, y, slope)) {
                    bowl = true;
                } else if (bowl) {
                    supported[half]++;
                    break;
                }
            }
        }
        return total[0] > 0
                && total[1] > 0
                && supported[0] >= total[0] * .65f
                && supported[1] >= total[1] * .65f;
    }

    private static boolean precedingClef(
            byte[] labels, int width, int height, int left, int firstLine, float gap, int[] shifts) {
        int rowCount = 0, pixels = 0;
        float neededRows = gap * 3.2f, neededPixels = gap * gap;
        int lo = Math.max(0, Math.round(left - gap * 13));
        int hi = Math.max(0, Math.round(left - gap * .45f));
        for (int y = Math.round(firstLine - gap); y <= Math.round(firstLine + gap * 5); y++) {
            boolean row = false;
            for (int x = lo; x < hi; x++) {
                int yy = y + (shifts == null ? 0 : shifts[x]);
                if (yy >= 0
                        && yy < height
                        && labels[yy * width + x] == OmrMeasurePostProcessor.CLEF_OR_KEY) {
                    row = true;
                    if (pixels < neededPixels) pixels++;
                    if (pixels >= neededPixels) break;
                }
            }
            if (row) rowCount++;
            if (rowCount >= neededRows && pixels >= neededPixels) return true;
        }
        return rowCount >= neededRows && pixels >= neededPixels;
    }

    /** A circular upper terminal may enter the central window while a lower channel stays open. */
    private static boolean openChannel(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            float mid,
            float gap,
            int firstLine,
            float slope) {
        int radius = Math.max(1, Math.round(gap * .12f));
        int x0 = Math.round(left + (right - left + 1) * .72f),
                needed = Math.max(2, (int) Math.ceil(gap * .12f));
        int clear = 0;
        float maxDark = (right - x0 + 1) * .16f;
        for (int y = Math.round(mid - gap * .30f); y <= Math.round(mid + gap * .30f); y++) {
            if (rule(y, firstLine, gap, radius)) continue;
            int dark = 0;
            for (int x = x0; x <= right; x++)
                if (ink(gray, width, height, x, y, slope) && ++dark > maxDark) break;
            if (dark <= maxDark) {
                if (++clear >= needed) return true;
            } else clear = 0;
        }
        return false;
    }

    private static int count(
            byte[] gray,
            int width,
            int height,
            float left,
            float right,
            float top,
            float bottom,
            int firstLine,
            float gap,
            float slope) {
        int n = 0, radius = Math.max(1, Math.round(gap * .12f));
        for (int y = Math.round(top); y <= Math.round(bottom); y++) {
            if (rule(y, firstLine, gap, radius)) continue;
            for (int x = Math.max(0, Math.round(left));
                    x <= Math.min(width - 1, Math.round(right));
                    x++) if (ink(gray, width, height, x, y, slope)) n++;
        }
        return n;
    }

    private static boolean rule(int y, int firstLine, float gap, int radius) {
        for (int i = 0; i < 5; i++) if (Math.abs(y - (firstLine + i * gap)) <= radius) return true;
        return false;
    }

    private static boolean ink(byte[] gray, int width, int height, int x, int y, float slope) {
        int yy = y + Math.round(slope * (x - width * .5f));
        return x >= 0 && x < width && inkAt(gray, width, height, x, yy);
    }
}
