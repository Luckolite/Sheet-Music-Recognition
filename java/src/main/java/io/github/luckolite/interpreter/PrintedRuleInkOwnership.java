// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** A locally wider printed rule retains the contrast and phase of its continuous source rail. */
final class PrintedRuleInkOwnership {
    private PrintedRuleInkOwnership() {}

    private static final float[] NEAR_OFFSETS = {-.9f, -.65f, -.4f, .4f, .65f, .9f};

    static boolean matches(
            byte[] gray,
            int w,
            int h,
            int x,
            int first,
            int last,
            float top,
            float gap,
            float slope,
            StaffPitchTrack track) {
        return matches(gray, w, h, x, first, last, top, gap, slope, track, false);
    }

    static boolean matchesWithNarrowReference(
            byte[] gray,
            int w,
            int h,
            int x,
            int first,
            int last,
            float top,
            float gap,
            float slope,
            StaffPitchTrack track) {
        return matches(gray, w, h, x, first, last, top, gap, slope, track, true);
    }

    private static boolean matches(
            byte[] gray,
            int w,
            int h,
            int x,
            int first,
            int last,
            float top,
            float gap,
            float slope,
            StaffPitchTrack track,
            boolean narrowReference) {
        if (track == null
                || !track.verified()
                || gray == null
                || w <= 0
                || h <= 0
                || gray.length != (long) w * h
                || !Float.isFinite(gap)
                || gap < 8
                || gap > Math.min(w, h) * .25f
                || !Float.isFinite(top)
                || first < 0
                || last < first
                || last >= h
                || x < 1
                || x >= w - 1) return false;
        float[] local = track.at(x);
        float localGap = local[1], localTop = local[0] - 4 * localGap;
        float center = (first + last) * .5f;
        int line = Math.round((center - localTop) / localGap);
        float phase = center - localTop - line * localGap;
        if (line < 0 || line > 4 || Math.abs(phase) > localGap * .25f) return false;
        int from = Math.round(gap * 2), to = Math.round(gap * 5);
        int sideLength = to - from + 1, total = sideLength * 2;
        int sideRequired = (int) Math.ceil(sideLength * .7f);
        int leftEnd = Math.min(to, x), rightEnd = Math.min(to, w - 1 - x);
        int leftColumns = leftEnd - from + 1, rightColumns = rightEnd - from + 1;
        if (leftColumns < sideRequired || rightColumns < sideRequired)
            return false;
        var sampler = new RuleSampler();
        int leftNear = 0, rightNear = 0;
        float nearContrast = 0, nearMass = 0;
        float nearTolerance = Math.max(1, gap * .1f);
        for (float off : NEAR_OFFSETS) {
            int xx = x + Math.round(off * gap);
            if (xx < 0 || xx >= w) continue;
            float[] f = track.at(xx);
            float staffTop = f[0] - 4 * f[1];
            if (!sampler.sample(gray, w, h, xx, staffTop + line * f[1] + phase, f[1], .55f))
                continue;
            if (Math.abs(sampler.center - staffTop - line * f[1] - phase) > nearTolerance)
                return false;
            if (off < 0) leftNear++;
            else rightNear++;
            nearContrast = Math.max(nearContrast, sampler.contrast);
            nearMass = Math.max(nearMass, sampler.mass);
        }
        if (leftNear < 2 || rightNear < 2) return false;
        int available = leftColumns + rightColumns;
        var frames = new float[available][];
        float[] contrasts = new float[available], masses = new float[available];
        float tolerance = Math.max(1, gap * .12f);
        int continued = 0;
        for (int side = -1; side <= 1; side += 2) {
            int before = continued;
            int end = side < 0 ? leftEnd : rightEnd;
            int column = side < 0 ? 0 : leftColumns;
            for (int dx = from, xx = x + side * from; dx <= end; dx++, xx += side, column++) {
                if (continued - before + end - dx + 1 < sideRequired) return false;
                float[] f = frames[column] = track.at(xx);
                f[0] -= 4 * f[1];
                if (!sampleReference(sampler, gray, w, h, xx, f, line, phase, tolerance, true))
                    continue;
                contrasts[continued] = sampler.contrast;
                masses[continued++] = sampler.mass;
            }
            if (continued - before < sideRequired) return false;
        }
        if (continued == 0) return false;
        Arrays.sort(contrasts, 0, continued);
        int rank = (int) ((continued - 1L) * 3 / 4);
        if (nearContrast > contrasts[rank] * 1.35f) return false;
        Arrays.sort(masses, 0, continued);
        float contrast = contrasts[rank], mass = masses[rank];
        if (nearMass > mass * 2f) return false;
        // Neighboring source rails corroborate the already verified five-rule frame.
        int witnesses = 0;
        int referenceRequired = (int) Math.ceil(total * .6f);
        float referenceMass = Float.POSITIVE_INFINITY;
        boolean contrastSupported = false, massSupported = false;
        int remainingRails = 4;
        reference:
        for (int other = 0; other < 5; other++) {
            if (other == line) continue;
            if (witnesses + remainingRails < 2) return false;
            remainingRails--;
            int hits = 0, lighter = 0, contrastHits = 0, massHits = 0;
            boolean countContrast = narrowReference || !contrastSupported;
            boolean countMass = !narrowReference && !massSupported;
            for (int side = -1; side <= 1; side += 2) {
                int end = side < 0 ? leftEnd : rightEnd;
                int column = side < 0 ? 0 : leftColumns;
                for (int dx = from, xx = x + side * from; dx <= end; dx++, xx += side, column++) {
                    int possibleHits = hits + available - column;
                    if (possibleHits < referenceRequired) continue reference;
                    // Prove the upper medians even if every remaining sample is present.
                    int worstCaseNeeded = possibleHits - possibleHits / 2;
                    if (!narrowReference && witnesses >= 1 && hits >= referenceRequired
                            && (contrastSupported || contrastHits >= worstCaseNeeded)
                            && (massSupported || massHits >= worstCaseNeeded)) return true;
                    if (!sampleReference(sampler, gray, w, h, xx, frames[column], other,
                            phase, tolerance, false)) continue;
                    if (narrowReference) {
                        masses[hits] = sampler.mass;
                        if ((float) sampler.mass < referenceMass) lighter++;
                    } else if (countMass && mass <= sampler.mass * 2f) {
                        massHits++;
                    }
                    if (countContrast && contrast <= sampler.contrast * 1.35f) contrastHits++;
                    hits++;
                }
            }
            if (hits >= referenceRequired) {
                witnesses++;
                // The upper median passes when this many samples reach the cutoff.
                int needed = hits - hits / 2;
                if (narrowReference) {
                    // One neighboring beam cannot raise a narrow-rule reference.
                    if (lighter <= hits / 2) continue;
                    Arrays.sort(masses, 0, hits);
                    float middleMass = masses[hits / 2];
                    referenceMass = middleMass;
                    contrastSupported = contrastHits >= needed;
                    massSupported = mass <= middleMass * 2f;
                } else {
                    contrastSupported |= contrastHits >= needed;
                    massSupported |= massHits >= needed;
                    if (witnesses >= 2 && contrastSupported && massSupported) return true;
                }
            }
        }
        return witnesses >= 2 && contrastSupported && massSupported;
    }

    private static boolean sampleReference(
            RuleSampler sampler, byte[] gray, int w, int h, int x, float[] frame,
            int line, float phase, float tolerance, boolean continuation) {
        float staffTop = frame[0];
        if (!sampler.sample(gray, w, h, x, staffTop + line * frame[1] + phase, frame[1], .4f))
            return false;
        float delta = Math.abs(sampler.center - staffTop - line * frame[1] - phase);
        return continuation ? !(delta > tolerance) : delta <= tolerance;
    }

    /** Center, maximum contrast, and ink deficit, using only local raw paper. */
    private static final class RuleSampler {
        private int[] values = new int[0], sorted = new int[0];

        float center;
        int contrast, mass;

        boolean sample(
                byte[] gray, int w, int h, int x, float seed, float gap, float maximum) {
            if (x < 0 || x >= w) return false;
            int lo = Math.max(1, Math.round(seed - gap * .55f)),
                    hi = Math.min(h - 2, Math.round(seed + gap * .55f));
            if (hi <= lo) return false;
            int size = hi - lo + 1;
            if (values.length < size) values = new int[size];
            int darkest = 255, lightest = 0, lightestCount = 0, yDark = -1;
            float centerTolerance = gap * .25f;
            for (int y = lo, pixel = lo * w + x; y <= hi; y++, pixel += w) {
                int v = gray[pixel] & 255;
                values[y - lo] = v;
                if (v > lightest) {
                    lightest = v;
                    lightestCount = 1;
                } else if (v == lightest) lightestCount++;
                if (Math.abs(y - seed) <= centerTolerance && v < darkest) {
                    darkest = v;
                    yDark = y;
                }
            }
            if (lightest - darkest < 24) return false;
            int rank = (size - 1) * 85 / 100;
            int paper = rank >= size - lightestCount ? lightest : paperTone(size, size - rank);
            if (paper - darkest < 24) return false;
            int threshold = paper - Math.max(20, Math.round((paper - darkest) * .28f));
            double maximumLength = Math.ceil(gap * maximum);
            int first = yDark, last = yDark;
            while (first > lo && values[first - lo - 1] < threshold) {
                first--;
                if (first == lo || last - first + 1 > maximumLength) return false;
            }
            while (last < hi && values[last - lo + 1] < threshold) {
                last++;
                if (last == hi || last - first + 1 > maximumLength) return false;
            }
            float cy = (first + last) * .5f;
            if (first == lo
                    || last == hi
                    || last - first + 1 > maximumLength
                    || Math.abs(cy - seed) > centerTolerance) return false;
            int mass = 0;
            for (int y = first; y <= last; y++) mass += Math.max(0, paper - values[y - lo]);
            center = cy;
            contrast = paper - darkest;
            this.mass = mass;
            return true;
        }

        private int paperTone(int size, int count) {
            if (count > 8) {
                if (sorted.length < size) sorted = new int[size];
                System.arraycopy(values, 0, sorted, 0, size);
                Arrays.sort(sorted, 0, size);
                return sorted[size - count];
            }
            if (sorted.length < count) sorted = new int[count];
            Arrays.fill(sorted, 0, count, -1);
            for (int i = 0; i < size; i++) {
                int tone = values[i];
                if (tone <= sorted[count - 1]) continue;
                int at = count - 1;
                while (at > 0 && tone > sorted[at - 1]) {
                    sorted[at] = sorted[at - 1];
                    at--;
                }
                sorted[at] = tone;
            }
            return sorted[count - 1];
        }
    }
}
