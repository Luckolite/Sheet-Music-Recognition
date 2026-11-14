// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Removes only independently corroborated rule contrast from a local curve witness. */
final class PrintedCurveRuleInk {
    private record Reference(float center, float contrast, float mass) {}

    private record Profile(float center, int referenceLines) {}

    private static final Profile NO_PROFILE = new Profile(0, 0);

    private static final class ColumnProof {
        final float top, gap;
        final float gapTolerance, centerTolerance;
        final int radius, maxThickness;
        final Profile[] profiles = new Profile[5];
        final Reference[] references = new Reference[5];
        int scannedLines;
        int fittedLines;
        float fittedTop, fittedGap;
        boolean fitVerified;

        ColumnProof(float top, float gap) {
            this.top = top;
            this.gap = gap;
            gapTolerance = gap * .15f;
            centerTolerance = gap * .25f;
            radius = Math.max(3, Math.round(gap * .35f));
            maxThickness = (int) Math.ceil(gap * .3f);
        }
    }

    private final byte[] labels, gray;
    private final int width, height, threshold;
    private final StaffPitchTrack track;
    private static final int COLUMN_BLOCK_SIZE = 64;
    private ColumnProof[][] columns;
    private ColumnProof columnProof;
    private final float[] samples = new float[4];
    private final int[] referenceScratch = new int[4];
    private int lastColumn = -1;
    private boolean columnVerified;
    private float columnTop, columnGap;
    private int firstCrossing = -1;
    private boolean corroborated;

    PrintedCurveRuleInk(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int threshold,
            StaffPitchTrack track) {
        this.labels = labels;
        this.gray = gray;
        this.width = width;
        this.height = height;
        this.threshold = threshold;
        this.track = track;
    }

    boolean corroboratedCrossing() {
        return corroborated;
    }

    boolean isInk(int x, int y) {
        int original = gray[y * width + x] & 255;
        return original < threshold && adjust(x, y, original) < threshold;
    }

    boolean removesInk(int x, int y) {
        int original = gray[y * width + x] & 255;
        return original < threshold && removesInk(x, y, original);
    }

    /** The caller has already read a pixel below the threshold. */
    boolean removesInk(int x, int y, int original) {
        return adjust(x, y, original) >= threshold;
    }

    int sample(int x, int y) {
        int original = gray[y * width + x] & 255;
        return original == 255 ? original : adjust(x, y, original);
    }

    private int adjust(int x, int y, int original) {
        selectColumn(x);
        if (!columnVerified) return original;
        return adjustInColumn(x, y, original);
    }

    private void selectColumn(int x) {
        if (lastColumn == x) return;
        lastColumn = x;
        columnVerified = track.verifiedAt(x);
        if (!columnVerified) return;
        ColumnProof[] block = columns == null ? null : columns[x / COLUMN_BLOCK_SIZE];
        columnProof = block == null ? null : block[x % COLUMN_BLOCK_SIZE];
        if (columnProof == null) {
            float[] frame = track.at(x);
            columnGap = frame[1];
            columnVerified = Float.isFinite(columnGap) && columnGap >= 8;
            columnTop = frame[0] - 4 * columnGap;
        } else {
            columnGap = columnProof.gap;
            columnTop = columnProof.top;
        }
    }

    /** Lighten only the five rule bands and keep earlier adjustments from overlapping staves. */
    byte[] lightenRuleBands(byte[] clean) {
        for (int x = 0; x < width; x++) {
            selectColumn(x);
            if (!columnVerified) continue;
            float radius = columnGap * .45f;
            for (int line = 0; line < 5; line++) {
                float center = columnTop + line * columnGap;
                int lo = Math.max(0, (int) Math.ceil(center - radius));
                int hi = Math.min(height - 1, (int) Math.floor(center + radius));
                for (int y = lo, at = lo * width + x; y <= hi; y++, at += width) {
                    int original = gray[at] & 255;
                    int previous = clean == null ? original : clean[at] & 255;
                    if (previous == 255) continue;
                    int value = adjustInColumn(x, y, original);
                    if (value <= previous) continue;
                    if (clean == null) clean = gray.clone();
                    clean[at] = (byte) value;
                }
            }
        }
        return clean;
    }

    private int adjustInColumn(int x, int y, int original) {
        int line = Math.round((y - columnTop) / columnGap);
        if (line < 0 || line > 4 || Math.abs(y - columnTop - line * columnGap) > columnGap * .45f)
            return original;
        if (columnProof == null) {
            columnProof = new ColumnProof(columnTop, columnGap);
            if (columns == null) columns = new ColumnProof[(width - 1) / COLUMN_BLOCK_SIZE + 1][];
            int blockIndex = x / COLUMN_BLOCK_SIZE;
            ColumnProof[] block = columns[blockIndex];
            if (block == null) block = columns[blockIndex] = new ColumnProof[COLUMN_BLOCK_SIZE];
            block[x % COLUMN_BLOCK_SIZE] = columnProof;
        }
        Profile profile = columnProof.profiles[line];
        if (profile == null) {
            profile = prove(x, line);
            if (profile == null) profile = NO_PROFILE;
            columnProof.profiles[line] = profile;
        }
        if (profile == NO_PROFILE) return original;
        int count = 0;
        for (int bits = profile.referenceLines; bits != 0; bits &= bits - 1) {
            int referenceLine = Integer.numberOfTrailingZeros(bits);
            samples[count++] = interpolate(
                    x, columnProof.references[referenceLine].center + y - profile.center);
        }
        float reference = upperMedian(samples, count);
        int adjusted = Math.min(255, Math.round(original + Math.max(0, 255 - reference)));
        if (!corroborated && original < threshold && adjusted >= threshold) {
            if (firstCrossing < 0) firstCrossing = x;
            else if (firstCrossing != x) corroborated = true;
        }
        return adjusted;
    }

    private Profile prove(int x, int target) {
        ColumnProof column = columnProof;
        int count = 0, missing = 0;
        for (int line = 0; line < 5; line++) {
            if (line == target) continue;
            int bit = 1 << line;
            if ((column.scannedLines & bit) == 0) {
                int center = Math.round(column.top + line * column.gap);
                column.references[line] = scanReference(
                        x, center, column.radius, column.maxThickness);
                column.scannedLines |= bit;
            }
            Reference reference = column.references[line];
            if (reference != null) referenceScratch[count++] = line;
            else if (++missing == 2) return null;
        }
        for (int i = 0; i < count; i++) samples[i] = column.references[referenceScratch[i]].contrast;
        float contrast = upperMedian(samples, count);
        for (int i = 0; i < count; i++) samples[i] = column.references[referenceScratch[i]].mass;
        float mass = upperMedian(samples, count);
        float minContrast = contrast / 1.35f, maxContrast = contrast * 1.35f;
        float minMass = mass / 1.35f, maxMass = mass * 1.35f;
        int accepted = 0, acceptedLines = 0, rejectionsLeft = count - 3;
        for (int i = 0; i < count; i++) {
            int line = referenceScratch[i];
            Reference r = column.references[line];
            if (r.contrast < minContrast || r.contrast > maxContrast
                    || r.mass < minMass || r.mass > maxMass) {
                if (--rejectionsLeft < 0) return null;
                continue;
            }
            referenceScratch[accepted++] = line;
            acceptedLines |= 1 << line;
        }
        if (column.fittedLines != acceptedLines) {
            float meanLine = 0, meanCenter = 0;
            for (int i = 0; i < accepted; i++) {
                int line = referenceScratch[i];
                meanLine += line;
                meanCenter += column.references[line].center;
            }
            meanLine /= accepted;
            meanCenter /= accepted;
            float covariance = 0, variance = 0;
            for (int i = 0; i < accepted; i++) {
                int line = referenceScratch[i];
                float lineOffset = line - meanLine;
                covariance += lineOffset * (column.references[line].center - meanCenter);
                variance += lineOffset * lineOffset;
            }
            float fittedGap = covariance / variance, fittedTop = meanCenter - fittedGap * meanLine;
            column.fittedLines = acceptedLines;
            column.fittedTop = fittedTop;
            column.fittedGap = fittedGap;
            column.fitVerified = false;
            if (Math.abs(fittedGap - column.gap) > column.gapTolerance) return null;
            for (int i = 0; i < accepted; i++) {
                int line = referenceScratch[i];
                if (Math.abs(column.references[line].center - fittedTop - line * fittedGap) > .75f)
                    return null;
            }
            column.fitVerified = true;
        }
        if (!column.fitVerified) return null;
        float center = column.fittedTop + target * column.fittedGap;
        if (Math.abs(center - column.top - target * column.gap) > column.centerTolerance)
            return null;
        return new Profile(center, acceptedLines);
    }

    /** Select the upper median of three or four finite reference values. */
    private static float upperMedian(float[] values, int count) {
        float low = Math.min(values[0], values[1]), high = Math.max(values[0], values[1]);
        if (count == 3) return Math.max(low, Math.min(high, values[2]));
        float otherLow = Math.min(values[2], values[3]), otherHigh = Math.max(values[2], values[3]);
        return Math.max(Math.min(high, otherHigh), Math.max(low, otherLow));
    }

    private Reference scanReference(int x, int center, int radius, int maxThickness) {
        int lo = center - radius, hi = center + radius;
        if (lo < 1 || hi >= height - 1
                || (gray[lo * width + x] & 255) < 230
                || (gray[hi * width + x] & 255) < 230) return null;
        int first = -1, last = -1;
        float mass = 0;
        int darkest = 255;
        for (int y = lo, pixel = lo * width + x; y <= hi; y++, pixel += width) {
            int value = gray[pixel] & 255;
            mass += 255 - value;
            darkest = Math.min(darkest, value);
            if (value >= threshold) continue;
            if (first < 0) first = y;
            else if (y != last + 1) return null;
            last = y;
            if (last - first + 1 > maxThickness) return null;
            int label = labels == null ? 0 : labels[pixel] & 255;
            if (label != 0 && label != OmrMeasurePostProcessor.STAFF) return null;
        }
        if (first <= lo || last >= hi || last - first + 1 < 2) return null;
        return new Reference((first + last) * .5f, 255 - darkest, mass);
    }

    private float interpolate(int x, float y) {
        int row = (int) Math.floor(y);
        if (row < 0 || row + 1 >= height) return 255;
        float fraction = y - row;
        int pixel = row * width + x;
        int upper = gray[pixel] & 255;
        if (fraction == 0) return upper;
        return upper * (1 - fraction) + (gray[pixel + width] & 255) * fraction;
    }
}
