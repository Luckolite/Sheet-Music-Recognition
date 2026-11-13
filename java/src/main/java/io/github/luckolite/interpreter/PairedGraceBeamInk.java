// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Counts parallel dark cores joining two independently attached stems. */
final class PairedGraceBeamInk {
    private PairedGraceBeamInk() {}

    private static final float[] CONTRASTS = {.5f, .75f, 1f};
    private static final float[] COMPACT_CONTRASTS = {.5f, .75f};
    private static final float[] COMPACT_PROBES = {.25f, .5f, .75f};
    private static final float[] TIP_INSETS = {.25f, .5f, .75f, 1f};
    private static final float[][] INNER_COMPACT_PROBES = {{.2f, .4f, .6f}, {.4f, .6f, .8f}};

    record ObservedBand(int x, int top, int bottom) {}

    record ObservedPair(
            byte[] gray,
            int width,
            int height,
            int[] first,
            int[] last,
            StaffPitchTrack track,
            java.util.List<ObservedBand> bands) {
        ObservedPair {
            first = first.clone();
            last = last.clone();
            bands = java.util.List.copyOf(bands);
        }

        public int[] first() {
            return first.clone();
        }

        public int[] last() {
            return last.clone();
        }
    }

    /** Collects the ordered evidence for each contrast that proves exactly two cores. */
    static java.util.List<ObservedPair> observeDoubleBands(
            byte[] gray,
            int w,
            int h,
            int[] a,
            int[] b,
            float gap,
            float staffTop,
            StaffPitchTrack track) {
        if (gray == null
                || a == null
                || b == null
                || a.length != 3
                || b.length != 3
                || w < 3
                || h < 3
                || gray.length != (long) w * h
                || !Float.isFinite(gap)
                || gap < 4
                || !Float.isFinite(staffTop)
                || a[0] < 1
                || b[0] >= w - 1
                || a[0] >= b[0]
                || a[1] < 0
                || a[1] >= h
                || b[1] < 0
                || b[1] >= h
                || Math.abs(a[2]) != 1
                || a[2] != b[2]
                || (long) b[0] - a[0] + 1 > gap * 40) return java.util.List.of();
        int span = b[0] - a[0];
        float edge = Math.min(.2f, Math.max(2, Math.round(gap * .15f)) / (float) span);
        float[] columns = {edge, .25f, .5f, .75f, 1 - edge};
        int[] tones = new int[columns.length];
        var results = new java.util.ArrayList<ObservedPair>();
        var bands = new java.util.ArrayList<ObservedBand>(10);
        for (float fraction : CONTRASTS) {
            bands.clear();
            int count = countAtContrast(
                    gray, w, h, a, b, gap, fraction, 2.2f, columns, staffTop, track,
                    false, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 2, 2, tones, bands);
            if (count == 2 && bands.size() == 10)
                results.add(new ObservedPair(gray, w, h, a, b, track, bands));
        }
        return java.util.List.copyOf(results);
    }

    static int count(byte[] gray, int width, int height, int[] a, int[] b, float gap) {
        return count(gray, width, height, a, b, gap, 1.5f, 3f, Float.NaN, null);
    }

    static int countFullSize(byte[] gray, int width, int height, int[] a, int[] b, float gap) {
        return count(gray, width, height, a, b, gap, 2.2f, 3f, Float.NaN, null);
    }

    /** Full-size written rails may join neighboring pitches across five staff gaps.
     * Every accepted core still crosses all five existing shaft-to-shaft probes. */
    static int countPrintedSize(byte[] gray, int width, int height, int[] a, int[] b, float gap) {
        return count(gray, width, height, a, b, gap, 2.2f, 5f, Float.NaN, null);
    }

    static int countPrintedSize(
            byte[] gray,
            int width,
            int height,
            int[] a,
            int[] b,
            float gap,
            float staffTop,
            StaffPitchTrack track) {
        return count(gray, width, height, a, b, gap, 2.2f, 5f, staffTop, track);
    }

    /** Confirm full-size rails against the independent local printed rules. */
    static int countFullSize(
            byte[] gray, int width, int height, int[] a, int[] b, float gap, float staffTop) {
        return count(gray, width, height, a, b, gap, 2.2f, 3f, staffTop, null);
    }

    static int countFullSize(
            byte[] gray,
            int width,
            int height,
            int[] a,
            int[] b,
            float gap,
            float staffTop,
            StaffPitchTrack track) {
        return count(gray, width, height, a, b, gap, 2.2f, 3f, staffTop, track);
    }

    /** Five freestanding probes beyond independently transported full-size shafts. */
    static int countTransported(
            byte[] gray,
            int width,
            int height,
            int[] a,
            int[] b,
            float gap,
            float staffTop,
            StaffPitchTrack track) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || track == null
                || !track.verified()
                || a == null
                || b == null
                || a.length != 3
                || b.length != 3
                || !Float.isFinite(gap)
                || gap < 4
                || gap > Math.min(width, height) * .25f
                || a[0] < 1
                || a[0] >= width - 1
                || b[0] < 1
                || b[0] >= width - 1
                || a[1] < 0
                || a[1] >= height
                || b[1] < 0
                || b[1] >= height
                || Math.abs(a[2]) != 1
                || a[2] != b[2]) return 0;
        int span = Math.abs(a[0] - b[0]);
        if (span < gap * .95f || span > gap * 3 || Math.abs(a[1] - b[1]) > gap * .75f) return 0;
        int sample = Math.max(4, Math.round(gap));
        float slopeA = (track.at(a[0] + sample)[0] - track.at(a[0] - sample)[0]) / (2 * sample);
        float slopeB = (track.at(b[0] + sample)[0] - track.at(b[0] - sample)[0]) / (2 * sample);
        float axis = b[0] > a[0] ? 1 : -1;
        float near = (2 + Math.max(0, slopeA * a[2] * axis * gap * 2.2f)) / span;
        float far = 1 - (2 + Math.max(0, -slopeB * b[2] * axis * gap * 2.2f)) / span;
        if (near >= .25f || far <= .75f) return 0;
        float[] probes = {near, .25f, .5f, .75f, far};
        int[] tones = new int[probes.length];
        int maximum = 0;
        for (float fraction : CONTRASTS) {
            int count = countAtContrast(
                    gray, width, height, a, b, gap, fraction, 2.2f, probes, staffTop, track, tones);
            if (count == 3) return 3;
            maximum = Math.max(maximum, count);
        }
        return maximum;
    }

    static int countSinglePrinted(
            byte[] gray,
            int width,
            int height,
            int[] a,
            int[] b,
            float gap,
            float staffTop,
            StaffPitchTrack track) {
        if (gray == null || a == null || b == null || gap < 4 || a[2] != b[2]) return 0;
        int span = Math.abs(a[0] - b[0]);
        if (span < gap * .95f || span > gap * 5 || Math.abs(a[1] - b[1]) > gap * .75f) return 0;
        float edge = Math.min(.2f, Math.max(2, Math.round(gap * .15f)) / (float) span);
        float[] columns = {edge, .25f, .5f, .75f, 1 - edge};
        int[] tones = new int[columns.length];
        for (float fraction : CONTRASTS)
            if (countAtContrast(
                            gray,
                            width,
                            height,
                            a,
                            b,
                            gap,
                            fraction,
                            2.2f,
                            columns,
                            staffTop,
                            track,
                            false,
                            Float.POSITIVE_INFINITY,
                            Float.POSITIVE_INFINITY,
                            1,
                            1,
                            tones,
                            null)
                    == 1) return 1;
        return 0;
    }

    private static int count(
            byte[] gray,
            int width,
            int height,
            int[] a,
            int[] b,
            float gap,
            float inside,
            float maximumSpan,
            float staffTop,
            StaffPitchTrack track) {
        if (gray == null || a == null || b == null || gap < 4 || a[2] != b[2]) return 0;
        int span = Math.abs(a[0] - b[0]);
        // Compact ornaments can rise one staff space between their stems. The
        // sampled cores below must still follow that same slope at every column.
        float maximumRise = inside == 1.5f ? gap : gap * .75f;
        if (span < gap * .95f || span > gap * maximumSpan || Math.abs(a[1] - b[1]) > maximumRise)
            return 0;
        int maximum = 0;
        // Keep the full-size probes beyond the shaft as the printed staff scales.
        float edge =
                Math.min(.2f, (inside == 1.5f ? 2f : Math.max(2, Math.round(gap * .15f))) / span);
        float[] probes =
                inside == 1.5f
                        ? COMPACT_PROBES
                        : new float[] {edge, .25f, .5f, .75f, 1 - edge};
        int[] tones = new int[probes.length];
        for (float fraction : CONTRASTS) {
            int count =
                    countAtContrast(
                            gray, width, height, a, b, gap, fraction, inside, probes, staffTop,
                            track, tones);
            if (count == 3 || (count > 0 && inside == 1.5f)) return count;
            maximum = Math.max(maximum, count);
        }
        // A full-size junction can blur the first freestanding witness into the shaft.
        // Move both end witnesses one core pixel inward, retaining all five columns
        // and accepting only three independently separated parallel printed rails.
        if (maximum == 0 && inside > 1.5f && span >= gap * 1.5f) {
            float inset = Math.min(.2f, Math.max(3, Math.round(gap * .22f)) / (float) span);
            // The three middle windows keep their geometry.
            if (inset != edge) {
                probes[0] = inset;
                probes[4] = 1 - inset;
                tones[0] = 0;
                tones[4] = 0;
            }
            for (float fraction : CONTRASTS) {
                int count =
                        countAtContrast(
                                gray, width, height, a, b, gap, fraction, inside, probes, staffTop,
                                track, false, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                                3, 3, tones, null);
                if (count == 3) return 3;
            }
        }
        if (maximum != 0 || inside != 1.5f || !compactProbeSpan(a, b)) return maximum;
        // A compact blurred pair can close the white channel close to one stem.
        // Require three separated, aligned double cores on the clear inner side;
        // this path is not used for full-size notes or very short connectors.
        float parallelTolerance = Math.max(1, gap * .12f);
        for (float[] columns : INNER_COMPACT_PROBES) {
            java.util.Arrays.fill(tones, 0);
            for (float fraction : COMPACT_CONTRASTS) {
                int count =
                        countAtContrast(
                                gray, width, height, a, b, gap, fraction, inside, columns,
                                staffTop, track, tones);
                if (count == 2) return count;
            }
        }
        // Integer-spaced witnesses must also preserve the distance between both rails.
        {
            float step = (float) Math.floor(span * .2f) / span;
            for (float[] columns :
                    new float[][] {
                        {step, 2 * step, 3 * step}, {1 - 3 * step, 1 - 2 * step, 1 - step}
                    }) {
                java.util.Arrays.fill(tones, 0);
                for (float fraction : COMPACT_CONTRASTS) {
                    int count =
                            countAtContrast(
                                    gray, width, height, a, b, gap, fraction, inside, columns,
                                    staffTop, track, true, parallelTolerance,
                                    Float.POSITIVE_INFINITY, 2, 2, tones, null);
                    if (count == 2) return count;
                }
            }
        }
        // A blurred junction may leave a clear channel on only one side.
        // Three disjoint strips must still prove two consistently spaced rails.
        {
            int step = Math.max(3, (int) Math.ceil(gap * .2f));
            int lastOffset = span - 3 - 2 * step;
            float[] columns = new float[3];
            int[] slidingTones = lastOffset >= 3 ? new int[span - 2] : null;
            for (int offset = 3; offset <= lastOffset; offset++) {
                for (int probe = 0; probe < columns.length; probe++) {
                    int position = offset + probe * step;
                    columns[probe] = position / (float) span;
                    tones[probe] = slidingTones[position];
                }
                float previousFraction = 0;
                for (int contrastStep = 4; contrastStep <= 12; contrastStep++) {
                    float fraction = contrastStep / 16f;
                    // A failed scan stays failed when every measured window
                    // keeps the same integer cutoff.
                    if (previousFraction != 0
                            && sameCutoff(tones[0], previousFraction, fraction)
                            && sameCutoff(tones[1], previousFraction, fraction)
                            && sameCutoff(tones[2], previousFraction, fraction)) continue;
                    int count =
                            countAtContrast(
                                    gray,
                                    width,
                                    height,
                                    a,
                                    b,
                                    gap,
                                    fraction,
                                    inside,
                                    columns,
                                    staffTop,
                                    track,
                                    true,
                                    parallelTolerance,
                                    parallelTolerance,
                                    2,
                                    2,
                                    tones,
                                    null);
                    if (count == 2) return count;
                    previousFraction = fraction;
                }
                for (int probe = 0; probe < columns.length; probe++)
                    slidingTones[offset + probe * step] = tones[probe];
            }
        }
        // A returning slur can extend one stem beyond its actual beam tip.
        // Retry bounded inward tip positions only for compact ornaments; every
        // accepted position still proves separate parallel cores at three columns.
        int[] first = a.clone(), last = b.clone();
        for (float inset : TIP_INSETS) {
            int shift = Math.round(gap * inset);
            for (int side = 0; side < 2; side++) {
                first[1] = side == 0 ? a[1] - a[2] * shift : a[1];
                last[1] = side == 1 ? b[1] - b[2] * shift : b[1];
                if (Math.abs(first[1] - last[1]) > gap) continue;
                java.util.Arrays.fill(tones, 0);
                for (float fraction : COMPACT_CONTRASTS) {
                    int count =
                            countAtContrast(
                                    gray, width, height, first, last, gap, fraction, inside,
                                    probes, staffTop, track, tones);
                    if (count >= 2) return count;
                }
            }
        }
        return maximum;
    }

    // Each three-pixel core witness must sample pixels independent of its neighbors.
    private static boolean compactProbeSpan(int[] a, int[] b) {
        for (float[] probes : INNER_COMPACT_PROBES) {
            int previous = -1;
            for (float f : probes) {
                int x = Math.round(a[0] + (b[0] - a[0]) * f);
                if (Math.abs(x - a[0]) < 2
                        || Math.abs(x - b[0]) < 2
                        || previous >= 0 && Math.abs(x - previous) < 3) return false;
                previous = x;
            }
        }
        return true;
    }

    private static int countAtContrast(
            byte[] gray, int width, int height, int[] a, int[] b, float gap, float fraction,
            float inside, float[] columns, float staffTop, StaffPitchTrack track, int[] tones) {
        return countAtContrast(
                gray, width, height, a, b, gap, fraction, inside, columns, staffTop, track,
                false, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 2, 3, tones, null);
    }

    private static boolean sameCutoff(int tones, float previous, float current) {
        int range = (tones >>> 8) - (tones & 255);
        return Math.round(range * previous) == Math.round(range * current);
    }

    private static int countAtContrast(
            byte[] gray, int width, int height, int[] a, int[] b, float gap, float fraction,
            float inside, float[] columns, float staffTop, StaffPitchTrack track,
            boolean strictParallel, float parallelTolerance, float coreTolerance,
            int minimumCores, int maximumCores, int[] tones,
            java.util.List<ObservedBand> observation) {
        int wanted = 0;
        float firstSeparation = Float.NaN;
        float[] previous = null;
        float firstCore = 0, secondCore = 0;
        float[] cores = null;
        boolean compact = inside == 1.5f;
        boolean hasStaffTop = Float.isFinite(staffTop);
        boolean verifiedTrack = track != null && track.verified();
        int minimumSize = Math.max(3, Math.round(gap * (compact ? .18f : .28f)));
        float maximumSize = gap * (compact ? .6f : .8f);
        double roundedSize = Math.ceil(gap * .4f);
        float minimumSpacing = gap * .3f, maximumSpacing = gap * .9f;
        float alignmentTolerance = gap * .22f;
        boolean compareFirstCores = Float.isFinite(coreTolerance);
        int span = b[0] - a[0], rise = b[1] - a[1];
        float above = (a[2] < 0 ? .35f : inside) * gap;
        float below = (a[2] < 0 ? inside : .35f) * gap;
        for (int probe = 0; probe < columns.length; probe++) {
            float f = columns[probe];
            int x = Math.round(a[0] + span * f);
            float end = a[1] + rise * f;
            int top = Math.max(1, Math.round(end - above));
            int bottom = Math.min(height - 2, Math.round(end + below));
            int packed = tones[probe];
            int threshold, darkest;
            if (packed == 0) {
                threshold = BeamInkThreshold.at(gray, width, height, x, top, bottom, gap);
                darkest = threshold;
                for (int y = top, pixel = top * width + x;
                        darkest > 0 && y <= bottom; y++, pixel += width)
                    darkest = Math.min(darkest, gray[pixel] & 255);
                // A cache belongs to a single probe geometry. Zero means that
                // previous attempts ended before reaching this probe.
                tones[probe] = (threshold << 8) | darkest;
            } else {
                threshold = packed >>> 8;
                darkest = packed & 255;
            }
            threshold = darkest + Math.round((threshold - darkest) * fraction);
            if (threshold == darkest) return 0;
            int requiredCores = wanted == 0 ? minimumCores : wanted;
            int allowedCores = wanted == 0 ? maximumCores : wanted;
            int coreCount = 0, start = -1;
            float[] physical = null;
            for (int y = top, pixel = top * width + x; y <= bottom + 1; y++, pixel += width) {
                boolean ink =
                        y <= bottom
                                && (gray[pixel - 1] & 255) < threshold
                                && (gray[pixel] & 255) < threshold
                                && (gray[pixel + 1] & 255) < threshold;
                if (ink && start < 0) start = y;
                if (!ink && start >= 0) {
                    int size = y - start;
                    if (!(size >= minimumSize && size <= maximumSize)) {
                        start = -1;
                        continue;
                    }
                    boolean staffCandidate = (hasStaffTop || verifiedTrack)
                            && (compact || (size <= roundedSize && exactRoundedCore(
                                    gray, width, height, x, start, y - 1, roundedSize, threshold)));
                    if (staffCandidate
                            && hasStaffTop
                            && LocalPrintedStaffBand.matches(
                                    gray, width, height, x, staffTop, gap, threshold, start,
                                    y - 1)) {
                        start = -1;
                        continue;
                    }
                    if (inside > 1.5f
                            && hasStaffTop
                            && staffCandidate
                            && LocalPrintedStaffBand.matchesRoundedBeyondStem(
                                    gray, width, height, x, staffTop, gap, threshold, start, y - 1,
                                    0, track)) {
                        start = -1;
                        continue;
                    }
                    if (staffCandidate && verifiedTrack) {
                        if (physical == null) physical = track.at(x);
                        if (LocalPrintedStaffBand.matches(
                                        gray,
                                        width,
                                        height,
                                        x,
                                        physical[0] - 4 * physical[1],
                                        physical[1],
                                        threshold,
                                        start,
                                        y - 1)
                                || PrintedRuleInkOwnership.matchesWithNarrowReference(
                                        gray,
                                        width,
                                        height,
                                        x,
                                        start,
                                        y - 1,
                                        physical[0] - 4 * physical[1],
                                        physical[1],
                                        0,
                                        track)) {
                            start = -1;
                            continue;
                        }
                    }
                    if (coreCount == allowedCores) return 0;
                    float core = (start + y - 1) * .5f - end;
                    if (coreCount > 0) {
                        float spacing = core - cores[coreCount - 1];
                        if (spacing < minimumSpacing || spacing > maximumSpacing) return 0;
                        if (strictParallel && wanted != 0
                                && Math.abs(spacing - firstSeparation) > parallelTolerance)
                            return 0;
                    }
                    if (previous != null
                            && Math.abs(core - previous[coreCount]) > alignmentTolerance) return 0;
                    if (compareFirstCores && wanted != 0
                            && Math.abs(core - (coreCount == 0 ? firstCore : secondCore))
                                    > coreTolerance) return 0;
                    if (cores == null) cores = new float[3];
                    cores[coreCount++] = core;
                    if (observation != null) observation.add(new ObservedBand(x, start, y - 1));
                    start = -1;
                }
            }
            if (coreCount < requiredCores) return 0;
            if (strictParallel && wanted == 0) firstSeparation = cores[1] - cores[0];

            // Only the strict two-core sliding probes supply a finite tolerance.
            if (compareFirstCores && wanted == 0) {
                firstCore = cores[0];
                secondCore = cores[1];
            }
            wanted = coreCount;
            float[] current = cores;
            cores = previous;
            previous = current;
        }
        return wanted;
    }

    // A nearby narrow rule cannot supply the mass of a different, wider beam core.
    private static boolean exactRoundedCore(
            byte[] gray,
            int width,
            int height,
            int x,
            int first,
            int last,
            double maximumSize,
            int threshold) {
        int top = first, bottom = last;
        double allowance = maximumSize - (last - first + 1);
        while (top > 0 && (gray[(top - 1) * width + x] & 255) < threshold) {
            top--;
            // The lower edge must grow almost as far to preserve the center.
            if (2L * (first - top) - 1 > allowance) return false;
        }
        while (bottom + 1 < height && (gray[(bottom + 1) * width + x] & 255) < threshold) {
            bottom++;
            if (bottom - top + 1 > maximumSize) return false;
        }
        return Math.abs((top + bottom) - (first + last)) <= 1;
    }
}
