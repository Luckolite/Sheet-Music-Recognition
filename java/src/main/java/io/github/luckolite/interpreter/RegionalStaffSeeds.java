// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Recovers row-local staff seeds without changing the source coordinate frame. */
final class RegionalStaffSeeds {
    record Seed(float top, float bottom, float gap, float slope) {}

    /** Exact quantized producer rows; no reconstruction from the fitted seed. */
    record Evidence(Seed seed, int[] rows, int offset) {
        Evidence {
            rows = rows.clone();
        }

        @Override
        public int[] rows() {
            return rows.clone();
        }
    }

    /** Follows a proved staff's changing printed spacing in the original source coordinates. */
    static StaffPitchTrack track(byte[] labels, byte[] gray, int width, int height, Seed seed) {
        if (labels == null
                || gray == null
                || width <= 0
                || height <= 0
                || (long) width * height != labels.length
                || labels.length != gray.length
                || seed == null
                || !Float.isFinite(seed.top)
                || !Float.isFinite(seed.bottom)
                || !Float.isFinite(seed.gap)
                || !Float.isFinite(seed.slope)
                || seed.gap < 3) return null;
        int stripWidth = Math.min(width, Math.max(64, Math.round(seed.gap * 16)));
        int first = Math.round(seed.top - seed.gap * 2);
        int localHeight = Math.max(24, Math.round(seed.gap * 8));
        int[] stripStarts = new int[13];
        int stripCount = 0;
        for (int strip = 0; strip < stripStarts.length; strip++) {
            float requested = width * (.08f + strip * (.86f / 12));
            int left = Math.max(0,
                    Math.min(width - stripWidth, Math.round(requested - stripWidth * .5f)));
            if (stripCount == 0 || left != stripStarts[stripCount - 1])
                stripStarts[stripCount++] = left;
        }
        float halfStrip = stripWidth * .5f, halfPage = width * .5f;
        float lastCenter = stripStarts[stripCount - 1] + halfStrip;
        if (stripCount < 6 || lastCenter - (stripStarts[0] + halfStrip) < halfPage)
            return null;
        List<float[]> samples = new ArrayList<>();
        byte[] localGray = new byte[stripWidth * localHeight];
        byte[] localLabels = new byte[localGray.length];
        float[] deskew = new float[stripWidth];
        int[] semantic = new int[localHeight], contrasted = new int[localHeight];
        int flank = Math.max(2, Math.round(seed.gap * .32f));
        int minimumStrength = Math.max(6, Math.round(stripWidth * .14f));
        int previousLeft = -1;
        float minimumGap = seed.gap * .75f, maximumGap = seed.gap * 1.25f;
        float bottomTolerance = minimumGap, topTolerance = seed.gap * 1.2f;
        for (int strip = 0; strip < stripCount; strip++) {
            if (samples.size() + stripCount - strip < 6) return null;
            int left = stripStarts[strip];
            int shift = previousLeft < 0 ? stripWidth : left - previousLeft;
            previousLeft = left;
            float centerX = left + halfStrip;
            if (samples.isEmpty() && lastCenter - centerX < halfPage) return null;
            // The contrast probes only read within the same column.
            boolean reuseStrength = shift < stripWidth - shift;
            if (reuseStrength)
                projectStripColumns(localGray, localLabels, stripWidth, flank,
                        0, shift, semantic, contrasted, -1, false);
            if (seed.slope == 0f) {
                for (int y = 0, at = 0; y < localHeight; y++, at += stripWidth) {
                    int worldY = Math.round(first + y + 0f);
                    if (worldY >= 0 && worldY < height) {
                        int source = worldY * width + left;
                        System.arraycopy(gray, source, localGray, at, stripWidth);
                        System.arraycopy(labels, source, localLabels, at, stripWidth);
                    } else {
                        Arrays.fill(localGray, at, at + stripWidth, (byte) 255);
                        Arrays.fill(localLabels, at, at + stripWidth, (byte) 0);
                    }
                }
            } else {
                // Deskew uses absolute columns, so overlap keeps the same source rows.
                int overlap = Math.max(0, stripWidth - shift);
                for (int x = overlap; x < stripWidth; x++)
                    deskew[x] = seed.slope * (left + x - width * .5f);
                for (int y = 0, row = 0; y < localHeight; y++, row += stripWidth) {
                    if (overlap > 0) {
                        System.arraycopy(localGray, row + shift, localGray, row, overlap);
                        System.arraycopy(localLabels, row + shift, localLabels, row, overlap);
                    }
                    for (int x = overlap, at = row + overlap; x < stripWidth; x++, at++) {
                        int worldY = Math.round(first + y + deskew[x]);
                        if (worldY >= 0 && worldY < height) {
                            int source = worldY * width + left + x;
                            localGray[at] = gray[source];
                            localLabels[at] = labels[source];
                        } else {
                            localGray[at] = (byte) 255;
                            localLabels[at] = 0;
                        }
                    }
                }
            }
            int firstNewColumn = reuseStrength ? stripWidth - shift : 0;
            projectStripColumns(localGray, localLabels, stripWidth, flank,
                    firstNewColumn, stripWidth, semantic, contrasted, 1, !reuseStrength);
            var semanticProposals =
                    RawStaffLineDetector.detectFromStrength(semantic, minimumStrength, localHeight);
            List<RawStaffLineDetector.StaffLines> contrastProposals = List.of();
            float[] best = null;
            float bestDistance = Float.POSITIVE_INFINITY;
            int semanticSize = semanticProposals.size();
            proposal:
            for (int i = 0; ; i++) {
                if (i == semanticSize) {
                    if (Arrays.equals(semantic, contrasted)) break;
                    contrastProposals = RawStaffLineDetector.detectFromStrength(
                            contrasted, minimumStrength, localHeight);
                }
                if (i >= semanticSize + contrastProposals.size()) break;
                var lines = i < semanticSize
                        ? semanticProposals.get(i)
                        : contrastProposals.get(i - semanticSize);
                if (i >= semanticSize) {
                    for (var previous : semanticProposals) {
                        if (lines.gap() == previous.gap() && Arrays.equals(lines.rows(), previous.rows()))
                            continue proposal;
                    }
                }
                if (lines.gap() < minimumGap
                        || lines.gap() > maximumGap
                        || Math.abs(lines.bottom() + first - seed.bottom) > bottomTolerance
                        || Math.abs(lines.top() + first - seed.top) > topTolerance
                        || !fivePrintedRules(
                                localGray,
                                stripWidth,
                                localHeight,
                                lines.rows(),
                                0,
                                lines.gap(),
                                0)) continue;
                float[] refined =
                        fittedRules(localGray, stripWidth, localHeight, lines.rows(), lines.gap());
                if (refined == null) continue;
                float distance = Math.abs(refined[0] + first - seed.bottom);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = refined;
                    if (distance == 0f) break;
                }
            }
            if (best != null && (samples.isEmpty() || centerX > samples.get(samples.size() - 1)[0])) {
                samples.add(new float[] {
                    centerX,
                    best[0] + first + seed.slope * (centerX - width * .5f),
                    best[1]
                });
            }
        }
        if (samples.size() < 6) return null;
        int pairs = samples.size() * (samples.size() - 1) / 2;
        float[] bottomSlopes = new float[pairs], gapSlopes = new float[pairs];
        sampleSlopes(samples, bottomSlopes, gapSlopes);
        float bottomSlope = median(bottomSlopes), gapSlope = median(gapSlopes);
        float[] bases = new float[samples.size()];
        float bottomBase = sampleBase(samples, 1, bottomSlope, bases),
                gapBase = sampleBase(samples, 2, gapSlope, bases);
        samples.removeIf(
                p ->
                        Math.abs(p[1] - bottomBase - bottomSlope * p[0]) > seed.gap * .35f
                                || Math.abs(p[2] - gapBase - gapSlope * p[0])
                                        > Math.max(.3f, seed.gap * .06f));
        if (samples.size() < 6
                || samples.get(samples.size() - 1)[0] - samples.get(0)[0] < width * .5f)
            return null;
        return StaffPitchTrack.fromVerifiedSamples(samples);
    }

    private static void projectStripColumns(
            byte[] gray, byte[] labels, int width, int flank, int firstColumn, int lastColumn,
            int[] semantic, int[] contrasted, int sign, boolean replace) {
        int flankOffset = flank * width;
        for (int y = flank; y < semantic.length - flank; y++) {
            int semanticCount = 0, contrastCount = 0;
            int row = y * width;
            for (int at = row + firstColumn, end = row + lastColumn; at < end; at++) {
                int ink = gray[at] & 255;
                if (labels[at] == OmrMeasurePostProcessor.STAFF) semanticCount++;
                if (ink <= 238
                        && (gray[at - flankOffset] & 255) >= ink + 12
                        && (gray[at + flankOffset] & 255) >= ink + 12)
                    contrastCount++;
            }
            semantic[y] = (replace ? 0 : semantic[y]) + sign * semanticCount;
            contrasted[y] = (replace ? 0 : contrasted[y]) + sign * contrastCount;
        }
    }

    private static float[] fittedRules(byte[] gray, int width, int height, int[] rows, float gap) {
        int probe = Math.max(2, Math.round(gap * .32f));
        int radius = Math.max(1, Math.round(gap * .10f));
        int probeOffset = probe * width;
        float[] centers = new float[5];
        for (int line = 0; line < 5; line++) {
            int firstRow = Math.max(probe, rows[line] - radius);
            int lastRow = Math.min(height - 1 - probe, rows[line] + radius);
            if (firstRow > lastRow) return null;
            int firstOffset = firstRow * width;
            double weighted = 0, weights = 0;
            for (int x = 0; x < width; x++)
                for (int y = firstRow, at = firstOffset + x; y <= lastRow; y++, at += width) {
                    int ink = gray[at] & 255;
                    if (ink > 238) continue;
                    int above = gray[at - probeOffset] & 255;
                    if (above < ink + 12) continue;
                    int below = gray[at + probeOffset] & 255;
                    if (below >= ink + 12) {
                        double weight = (above + below) * .5 - ink;
                        weighted += y * weight;
                        weights += weight;
                    }
                }
            if (weights == 0) return null;
            centers[line] = (float) (weighted / weights);
        }
        float mean = 0, spacing = 0;
        for (float center : centers) mean += center * .2f;
        for (int i = 0; i < 5; i++) spacing += (i - 2) * centers[i] * .1f;
        for (int i = 0; i < 5; i++)
            if (Math.abs(centers[i] - (mean + (i - 2) * spacing)) > gap * .12f) return null;
        return spacing >= 3 ? new float[] {mean + spacing * 2, spacing} : null;
    }

    private static void sampleSlopes(List<float[]> samples, float[] bottom, float[] gap) {
        int next = 0;
        for (int i = 0; i < samples.size(); i++) {
            float[] a = samples.get(i);
            for (int j = i + 1; j < samples.size(); j++) {
                float[] b = samples.get(j);
                float distance = b[0] - a[0];
                bottom[next] = (b[1] - a[1]) / distance;
                gap[next] = (b[2] - a[2]) / distance;
                next++;
            }
        }
    }

    private static float sampleBase(
            List<float[]> samples, int field, float slope, float[] bases) {
        for (int i = 0; i < bases.length; i++) {
            float[] sample = samples.get(i);
            bases[i] = sample[field] - slope * sample[0];
        }
        return median(bases);
    }

    private static float median(float[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }

    static List<Seed> detect(byte[] labels, byte[] gray, int width, int height) {
        List<Seed> seeds = new ArrayList<>();
        for (Evidence evidence : detectEvidence(labels, gray, width, height))
            seeds.add(evidence.seed());
        return List.copyOf(seeds);
    }

    static List<Evidence> detectEvidence(byte[] labels, byte[] gray, int width, int height) {
        if (labels == null
                || gray == null
                || width <= 0
                || height <= 0
                || (long) width * height != labels.length
                || labels.length != gray.length) return List.of();
        int left = Math.round(width * .08f), right = Math.round(width * .94f);
        int minimum = Math.max(10, width / 80), hole = Math.max(3, width / 170);
        int margin = Math.max(8, width / 50);
        int maximumEnvelope = Math.max(width / 3, height / 4);
        List<Evidence> result = new ArrayList<>();
        int first = -1, lastInk = -1, total = 0;
        for (int y = 0, row = 0; y <= height; y++, row += width) {
            int count = 0;
            if (y < height) {
                for (int at = row + left, end = row + right; at < end; at++)
                    if (labels[at] == OmrMeasurePostProcessor.STAFF) count++;
            }
            boolean ink = count >= minimum;
            if (ink) {
                if (first < 0) first = y;
                lastInk = y;
                total += count;
            }
            if (first >= 0 && ((!ink && y - lastInk > hole) || y == height)) {
                if (lastInk - first >= hole
                        && lastInk - first < maximumEnvelope
                        && total >= width * 2)
                    recover(
                            labels,
                            gray,
                            width,
                            height,
                            Math.max(0, first - margin),
                            Math.min(height, lastInk + margin + 1),
                            result);
                first = -1;
                lastInk = -1;
                total = 0;
            }
        }
        result.sort(Comparator.comparingDouble(value -> value.seed().top()));
        return List.copyOf(result);
    }

    private static void recover(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int first,
            int last,
            List<Evidence> result) {
        int localHeight = last - first;
        if (localHeight <= 0) return;
        byte[] local = Arrays.copyOfRange(labels, first * width, last * width);
        float slope = OmrMeasurePostProcessor.estimateStaffSlope(local, width, localHeight);
        int[] projection = new int[localHeight];
        for (int y = 0, rowStart = 0; y < localHeight; y++, rowStart += width)
            for (int x = 0; x < width; x++)
                if (local[rowStart + x] == OmrMeasurePostProcessor.STAFF) {
                    int row = Math.round(y - slope * (x - width * .5f));
                    if (row >= 0 && row < localHeight) projection[row]++;
                }
        for (var lines :
                RawStaffLineDetector.detectFromStrength(
                        projection, Math.max(10, width / 80), localHeight)) {
            Seed candidate =
                    new Seed(lines.top() + first, lines.bottom() + first, lines.gap(), slope);
            boolean represented = false;
            float center = (candidate.top + candidate.bottom) * .5f;
            for (Evidence oldEvidence : result) {
                Seed old = oldEvidence.seed();
                float gap = Math.min(old.gap, candidate.gap);
                if (Math.abs((old.top + old.bottom) * .5f - center) <= gap * 2
                        && Math.abs(old.gap - candidate.gap) <= gap * .18f) {
                    represented = true;
                    break;
                }
            }
            if (!represented
                    && fivePrintedRules(gray, width, height, lines.rows(), first, lines.gap(), slope))
                result.add(new Evidence(candidate, lines.rows(), first));
        }
    }

    /** Each of the five proposed rules needs its own long, thin raw-ink proof. */
    static boolean fivePrintedRules(
            byte[] gray, int width, int height, int[] rows, int offset, float gap, float slope) {
        if (gray == null
                || width <= 0
                || height <= 0
                || (long) width * height != gray.length
                || rows == null
                || rows.length != 5
                || gap < 3
                || !Float.isFinite(slope)) return false;
        int probe = Math.max(2, Math.round(gap * .32f));
        int radius = Math.max(0, Math.round(gap * .10f));
        int minimum = Math.max(24, Math.round(width * .08f));
        int minimumRun = Math.max(8, Math.round(gap * 3));
        if (width < minimum || width < minimumRun) return false;
        int probeOffset = probe * width;
        float middle = width * .5f;
        boolean level = slope == 0f;
        for (int line : rows) {
            int count = 0, run = 0;
            boolean longRun = false;
            int base = line + offset;
            int levelCenter = Math.round((float) base);
            int levelFirstRow = (int) Math.max(probe, (long) levelCenter - radius);
            int levelLastRow = (int) Math.min(height - 1 - probe, (long) levelCenter + radius);
            if (level && levelFirstRow > levelLastRow) return false;
            for (int x = 0; x < width; x++) {
                int center = level ? levelCenter : Math.round(base + slope * (x - middle));
                boolean supported = false;
                int firstRow = level ? levelFirstRow : (int) Math.max(probe, (long) center - radius);
                int lastRow = level ? levelLastRow
                        : (int) Math.min(height - 1 - probe, (long) center + radius);
                for (int y = firstRow, at = firstRow * width + x;
                        y <= lastRow; y++, at += width) {
                    int ink = gray[at] & 255;
                    if (ink <= 238
                            && (gray[at - probeOffset] & 255) >= ink + 12
                            && (gray[at + probeOffset] & 255) >= ink + 12) {
                        supported = true;
                        break;
                    }
                }
                if (supported) {
                    count++;
                    if (!longRun && ++run >= minimumRun) longRun = true;
                } else run = 0;
                if (count >= minimum && longRun) break;
                int remaining = width - 1 - x;
                if (count + remaining < minimum || (!longRun && run + remaining < minimumRun))
                    return false;
            }
            if (count < minimum || !longRun) return false;
        }
        return true;
    }
}
