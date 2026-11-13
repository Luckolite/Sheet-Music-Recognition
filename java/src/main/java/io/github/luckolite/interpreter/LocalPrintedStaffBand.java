// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** Other printed rules supply the local thickness and ink budget of a blurred rule. */
final class LocalPrintedStaffBand {
    private LocalPrintedStaffBand() {}

    static boolean matches(
            byte[] gray,
            int w,
            int h,
            int x,
            float top,
            float gap,
            int threshold,
            int first,
            int last) {
        return matches(gray, w, h, x, top, gap, threshold, first, last, 0);
    }

    static boolean matches(
            byte[] gray,
            int w,
            int h,
            int x,
            float top,
            float gap,
            int threshold,
            int first,
            int last,
            float slope) {
        return matches(gray, w, h, x, top, gap, threshold, first, last, slope, null);
    }

    static boolean matches(
            byte[] gray,
            int w,
            int h,
            int x,
            float top,
            float gap,
            int threshold,
            int first,
            int last,
            float slope,
            StaffPitchTrack track) {
        return matchesAtDistance(
                gray, w, h, x, top, gap, threshold, first, last, slope, track, false, false);
    }

    static boolean matchesBeyondStem(
            byte[] gray,
            int w,
            int h,
            int x,
            float top,
            float gap,
            int threshold,
            int first,
            int last,
            float slope,
            StaffPitchTrack track) {
        return matchesAtDistance(
                gray, w, h, x, top, gap, threshold, first, last, slope, track, true, false);
    }

    // The extra rounded pixel needs a typical rule budget; one beam cannot inflate it.
    static boolean matchesRoundedBeyondStem(
            byte[] gray,
            int w,
            int h,
            int x,
            float top,
            float gap,
            int threshold,
            int first,
            int last,
            float slope,
            StaffPitchTrack track) {
        return matchesAtDistance(
                gray, w, h, x, top, gap, threshold, first, last, slope, track, true, true);
    }

    private static boolean matchesAtDistance(
            byte[] gray,
            int w,
            int h,
            int x,
            float top,
            float gap,
            int threshold,
            int first,
            int last,
            float slope,
            StaffPitchTrack track,
            boolean bright,
            boolean medianReference) {
        if (track != null) {
            float[] frame = track.at(x);
            gap = frame[1];
            top = frame[0] - 4 * gap;
        }
        if (gray == null
                || gap < 8
                || x < 1
                || x >= w - 1
                || first < 0
                || last >= h
                || last < first) return false;
        float cy = (first + last) * .5f;
        int bandLine = Math.round((cy - top) / gap);
        if (bandLine < 0 || bandLine > 4 || Math.abs(cy - top - bandLine * gap) > gap * .3f)
            return false;
        int nearStart = Math.round(gap), span = Math.round(gap * 2);
        boolean nearFits = referenceFits(w, x, nearStart, span);
        boolean farFits = bright && referenceFits(w, x, span, span);
        if (!nearFits && !farFits) return false;
        var sample = new RuleSample();
        if (!sample.read(gray, w, h, x, cy, gap, threshold)
                || sample.paper < 60 || (bright ? sample.paper < 185 : sample.paper >= 185))
            return false;
        float nearMass = sample.mass;
        int overlap = nearFits && farFits && nearStart < span ? nearStart : 0;
        int firstDistance = nearFits ? nearStart : span;
        int distances = span + (overlap == 0 ? 0 : span - overlap);
        var scratch = new ReferenceScratch(
                span, medianReference, track != null, overlap, firstDistance, distances);
        if (farFits
                && referencesMatch(gray, w, h, x, top, gap, threshold, slope, track,
                        cy, bandLine, sample, nearMass, span, span, scratch)) return true;
        return nearFits && (!farFits || span != nearStart)
                && referencesMatch(gray, w, h, x, top, gap, threshold, slope, track,
                        cy, bandLine, sample, nearMass, nearStart, span, scratch);
    }

    private static boolean referenceFits(int width, int x, int start, int span) {
        return (long) x - start - span >= 1 && (long) x + start + span < width - 1;
    }

    private static final class ReferenceScratch {
        final float[] phases = new float[4];
        final float[] referenceMasses, masses, offsets;
        final float[][] frames;
        final int overlap, firstDistance, distances;
        float[] cachedMasses, cachedOffsets;

        ReferenceScratch(
                int span, boolean median, boolean curved, int overlap,
                int firstDistance, int distances) {
            referenceMasses = median ? new float[4] : null;
            masses = new float[span * 2];
            offsets = new float[span * 2];
            frames = curved ? new float[distances * 2][] : null;
            this.overlap = overlap;
            this.firstDistance = firstDistance;
            this.distances = distances;
        }

        void remember(int index, float mass, float offset) {
            if (cachedMasses == null) {
                cachedMasses = new float[10 * overlap];
                cachedOffsets = new float[10 * overlap];
            }
            cachedMasses[index] = mass;
            cachedOffsets[index] = offset;
        }
    }

    private static boolean referencesMatch(
            byte[] gray, int w, int h, int x, float top, float gap, int threshold, float slope,
            StaffPitchTrack track, float cy, int bandLine, RuleSample sample, float nearMass,
            int start, int span, ReferenceScratch scratch) {
        int references = 0;
        float largestMass = 0;
        float[] phases = scratch.phases, referenceMasses = scratch.referenceMasses;
        float[] masses = scratch.masses, offsets = scratch.offsets;
        float[][] referenceFrames = scratch.frames;
        boolean far = start == span;
        int columns = span * 2;
        int minimumSamples = (int) Math.ceil(columns * .55f);
        int minimumSide = (int) Math.ceil(span * .4f);
        float phaseTolerance = Math.max(1, gap * .1f);
        int overlapStart = span - start, overlapEnd = overlapStart + scratch.overlap;
        int remainingLines = 4;
        for (int line = 0; line < 5; line++) {
            if (line == bandLine) continue;
            if (references + remainingLines < 3) return false;
            remainingLines--;
            float seed = top + line * gap;
            int n = 0;
            boolean enoughSamples = true;
            for (int side = -1, column = 0; side <= 1; side += 2) {
                int sideStart = n;
                int sideIndex = side > 0 ? 1 : 0;
                int cacheBase = (2 * line + sideIndex) * scratch.overlap - overlapStart;
                int frameBase = start - scratch.firstDistance + sideIndex * scratch.distances;
                for (int i = 0; i < span; i++, column++) {
                    if (n - sideStart + span - i < minimumSide
                            || n + (columns - column) < minimumSamples) break;
                    int distance = start + i;
                    int cached = i >= overlapStart && i < overlapEnd ? cacheBase + i : -1;
                    if (!far && cached >= 0 && scratch.cachedMasses != null
                            && scratch.cachedMasses[cached] != 0) {
                        if (scratch.cachedMasses[cached] > 0) {
                            masses[n] = scratch.cachedMasses[cached];
                            offsets[n++] = scratch.cachedOffsets[cached];
                        }
                        continue;
                    }
                    int sampleX = x + side * distance;
                    float localSeed = seed + slope * side * distance;
                    if (referenceFrames != null) {
                        int frameIndex = frameBase + i;
                        float[] frame = referenceFrames[frameIndex];
                        if (frame == null) referenceFrames[frameIndex] = frame = track.at(sampleX);
                        localSeed = frame[0] - (4 - line) * frame[1];
                    }
                    if (!sample.read(gray, w, h, sampleX, localSeed, gap, threshold)) {
                        if (far && cached >= 0) scratch.remember(cached, -1, 0);
                        continue;
                    }
                    masses[n] = sample.mass;
                    offsets[n] = sample.center - localSeed;
                    if (far && cached >= 0) scratch.remember(cached, masses[n], offsets[n]);
                    n++;
                }
                if (n - sideStart < minimumSide
                        || n + (side < 0 ? span : 0) < minimumSamples) {
                    enoughSamples = false;
                    break;
                }
            }
            if (!enoughSamples) continue;
            Arrays.sort(offsets, 0, n);
            int middle = n / 2;
            float phase = offsets[middle];
            int allowedOutliers = n - (int) Math.ceil(n * .85f);
            for (int i = 0; i < middle && allowedOutliers >= 0
                    && Math.abs(offsets[i] - phase) > phaseTolerance; i++) allowedOutliers--;
            for (int i = n - 1; i > middle && allowedOutliers >= 0
                    && Math.abs(offsets[i] - phase) > phaseTolerance; i--) allowedOutliers--;
            if (allowedOutliers < 0) continue;
            Arrays.sort(masses, 0, n);
            float medianMass = masses[middle];
            if (referenceMasses != null) referenceMasses[references] = medianMass;
            phases[references++] = phase;
            largestMass = Math.max(largestMass, medianMass);
        }
        if (references < 3 || largestMass <= 0) return false;
        if (referenceMasses != null) {
            Arrays.sort(referenceMasses, 0, references);
            largestMass = referenceMasses[references / 2];
        }
        Arrays.sort(phases, 0, references);
        float phase = phases[references / 2];
        int agreeing = 0;
        for (int i = 0; i < references; i++)
            if (Math.abs(phases[i] - phase) <= gap * .15f) agreeing++;
        return agreeing >= 3
                && Math.abs(cy - top - bandLine * gap - phase) <= gap * .2f
                && nearMass <= largestMass * 1.4f;
    }

    /** Center, ink deficit, and local paper level of a narrow isolated source rule. */
    private static final class RuleSample {
        private int[] values = new int[0];
        private int[] brightest = new int[0];
        float center;
        int mass, paper;

        boolean read(byte[] gray, int w, int h, int x, float seed, float gap, int threshold) {
            if (x < 0 || x >= w) return false;
            int lo = Math.max(1, Math.round(seed - gap * .6f)),
                    hi = Math.min(h - 2, Math.round(seed + gap * .6f));
            if (hi - lo < 2) return false;
            int size = hi - lo + 1;
            if (values.length < size) values = new int[size];
            int pixel = lo * w + x;
            int lightest = values[0] = gray[pixel] & 255;
            int lightestCount = 1, darkest = 255;
            for (int i = 1; i < size - 1; i++) {
                int value = gray[pixel + w] & 255;
                pixel += w;
                values[i] = value;
                darkest = Math.min(darkest, value);
                if (value > lightest) {
                    lightest = value;
                    lightestCount = 1;
                } else if (value == lightest) lightestCount++;
            }
            int endTone = values[size - 1] = gray[pixel + w] & 255;
            if (endTone > lightest) {
                lightest = endTone;
                lightestCount = 1;
            } else if (endTone == lightest) lightestCount++;
            if (darkest >= threshold || lightest - darkest < 24) return false;
            int rank = (size - 1) * 85 / 100;
            if (rank >= size - lightestCount) {
                paper = lightest;
            } else {
                paper = paperTone(size, size - rank);
            }
            if (paper - darkest < 24) return false;
            float bestCenter = 0;
            int bestMass = 0;
            float distance = Float.POSITIVE_INFINITY;
            double maximumLength = Math.ceil(gap * .4f);
            float maximumDistance = gap * .3f;
            int start = -1;
            for (int y = lo; y <= hi; y++) {
                boolean ink = values[y - lo] < threshold;
                if (ink && start < 0) start = y;
                if (!ink && start >= 0) {
                    int length = y - start;
                    float center = (start + y - 1) * .5f;
                    float delta = Math.abs(center - seed);
                    if (start > lo
                            && length <= maximumLength
                            && delta <= maximumDistance
                            && delta < distance) {
                        int mass = Math.max(0, paper - values[start - 1 - lo])
                                + Math.max(0, paper - values[y - lo]);
                        int dark = 255;
                        for (int yy = start; yy < y; yy++) {
                            int value = values[yy - lo];
                            mass += Math.max(0, paper - value);
                            dark = Math.min(dark, value);
                        }
                        if (paper - dark >= 24) {
                            bestCenter = center;
                            bestMass = mass;
                            distance = delta;
                        }
                    }
                    start = -1;
                }
            }
            center = bestCenter;
            mass = bestMass;
            return bestMass != 0;
        }

        private int paperTone(int size, int count) {
            // Typical staff windows need only a few of the brightest pixels.
            if (count > 8) {
                if (brightest.length < size) brightest = new int[size];
                System.arraycopy(values, 0, brightest, 0, size);
                Arrays.sort(brightest, 0, size);
                return brightest[size - count];
            }
            if (brightest.length < count) brightest = new int[count];
            Arrays.fill(brightest, 0, count, -1);
            for (int i = 0; i < size; i++) {
                int tone = values[i];
                if (tone <= brightest[count - 1]) continue;
                int at = count - 1;
                while (at > 0 && tone > brightest[at - 1]) {
                    brightest[at] = brightest[at - 1];
                    at--;
                }
                brightest[at] = tone;
            }
            return brightest[count - 1];
        }
    }
}
