// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Follows complete printed five-line groups across a curved page. */
final class StaffPitchTrack {
    private static final float[] LOCAL_SLOPES = {0, .04f, -.04f, .08f, -.08f, .12f, -.12f, .16f, -.16f};

    private static final ThreadLocal<RuleProjection> RETRY_PROJECTION =
            ThreadLocal.withInitial(RuleProjection::new);

    /** Contrast depends on the physical pixel and flank, independent of deskew. */
    private static final class RuleProjection {
        private static final int INITIAL_CACHE_CELLS = 16384;
        private static final int RETRY_CACHE_CELLS = 131072;
        private static final java.lang.invoke.VarHandle GRAY_WORDS =
                java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                        long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);
        private static final long HIGH_BITS = 0x8080808080808080L;
        char[] prefix, storage;
        long[] masks, maskStorage;
        int readyFrom = Integer.MAX_VALUE, readyTo = -1;
        int[] strength, semanticShifts;
        float[] fitScratch;
        int firstRow, left, stride, excludedFrom, excludedTo, cells;
        boolean prepared;

        void reset() {
            prepared = false;
            prefix = null;
            masks = null;
            readyFrom = Integer.MAX_VALUE;
            readyTo = -1;
        }

        void prepare(int left, int right, int first, int last, int excludedLeft, int excludedRight) {
            prepared = true;
            firstRow = first;
            this.left = left;
            stride = right - left + 2;
            excludedFrom = Math.max(left, excludedLeft) - left + 1;
            excludedTo = Math.min(right, excludedRight) - left + 1;
            int rows = last - first + 1;
            long size = (long) rows * stride;
            cells = rows > 0 && stride >= 2 && size <= RETRY_CACHE_CELLS ? (int) size : 0;
            if (cells <= INITIAL_CACHE_CELLS) enableCache();
        }

        void enableCache() {
            if (cells == 0 || hasCache()) return;
            int leftColumns = excludedFrom <= excludedTo ? excludedFrom - 1 : 0;
            int rightStart = excludedFrom <= excludedTo ? excludedTo : 0;
            if (leftColumns <= Long.SIZE && stride - 1 - rightStart <= Long.SIZE) {
                int words = (cells / stride) * 2;
                if (maskStorage == null || maskStorage.length < words) maskStorage = new long[words];
                masks = maskStorage;
                return;
            }
            if (storage == null || storage.length < cells) storage = new char[cells];
            prefix = storage;
        }

        boolean hasCache() {
            return prefix != null || masks != null;
        }

        void accumulate(byte[] gray, int width, int start, int end, int shift, int top,
                int from, int to, int flankOffset, int threshold, int[] strength) {
            if (start > end) return;
            int first = start + shift - firstRow, last = end + shift - firstRow;
            ensureRows(gray, width, first, last, flankOffset, threshold);
            if (masks != null) {
                int column = from - left;
                boolean onLeft = excludedFrom <= excludedTo && column < excludedFrom - 1;
                int startBit = column - (onLeft || excludedFrom > excludedTo ? 0 : excludedTo);
                int bitCount = to - from + 1;
                long mask = (-1L >>> (Long.SIZE - bitCount)) << startBit;
                for (int row = start - top, word = first * 2 + (onLeft ? 0 : 1);
                        row <= end - top; row++, word += 2)
                    strength[row] += Long.bitCount(masks[word] & mask);
                return;
            }
            int base = first * stride;
            int fromIndex = base + from - left, toIndex = base + to - left + 1;
            for (int row = start - top, lastRow = end - top; row <= lastRow;
                    row++, fromIndex += stride, toIndex += stride) {
                strength[row] += prefix[toIndex] - prefix[fromIndex];
            }
        }

        void projectHorizontal(byte[] gray, int width, int start, int end, int top,
                int flankOffset, int threshold, int[] strength) {
            int first = start - firstRow, last = end - firstRow;
            ensureRows(gray, width, first, last, flankOffset, threshold);
            if (masks != null) {
                for (int row = start - top, word = first * 2; row <= end - top; row++, word += 2)
                    strength[row] = Long.bitCount(masks[word]) + Long.bitCount(masks[word + 1]);
                return;
            }
            // Each side starts at zero, so its end already holds the full count.
            int leftEnd = excludedFrom <= excludedTo ? excludedFrom - 1 : 0;
            int rightEnd = stride - 1;
            for (int row = start - top, base = first * stride; row <= end - top;
                    row++, base += stride) {
                strength[row] = prefix[base + leftEnd] + prefix[base + rightEnd];
            }
        }

        private void ensureRows(byte[] gray, int width, int first, int last, int flankOffset,
                int threshold) {
            if (first < readyFrom || last > readyTo) {
                if (readyTo < 0) {
                    populate(gray, width, first, last, flankOffset, threshold);
                } else {
                    populate(gray, width, first, readyFrom - 1, flankOffset, threshold);
                    populate(gray, width, readyTo + 1, last, flankOffset, threshold);
                }
                readyFrom = Math.min(readyFrom, first);
                readyTo = Math.max(readyTo, last);
            }
        }

        // Deskew moves the same physical row interval by a few pixels per trial.
        private void populate(byte[] gray, int width, int first, int last, int flankOffset,
                int threshold) {
            int at = (firstRow + first) * width + left;
            if (masks != null) {
                int leftColumns = excludedFrom <= excludedTo ? excludedFrom - 1 : 0;
                int rightStart = excludedFrom <= excludedTo ? excludedTo : 0;
                for (int row = first, word = first * 2; row <= last;
                        row++, word += 2, at += width) {
                    masks[word] = contrastMask(gray, at, leftColumns, flankOffset, threshold);
                    masks[word + 1] = contrastMask(
                            gray, at + rightStart, stride - 1 - rightStart, flankOffset, threshold);
                }
                return;
            }
            for (int row = first, base = first * stride; row <= last;
                    row++, base += stride, at += width) {
                int pixel = at;
                prefix[base] = 0;
                int out = base + 1;
                if (excludedFrom <= excludedTo) {
                    fill(gray, pixel, out, base + excludedFrom, flankOffset, threshold);
                    // Each prefix starts at zero. No query crosses the excluded span.
                    prefix[base + excludedTo] = 0;
                    out = base + excludedTo + 1;
                    pixel += excludedTo;
                }
                fill(gray, pixel, out, base + stride, flankOffset, threshold);
            }
        }

        private void fill(byte[] gray, int at, int out, int end, int flankOffset, int threshold) {
            int count = 0;
            for (; out < end; at++, out++) {
                int ink = gray[at] & 255;
                if (ink <= threshold
                        && (gray[at - flankOffset] & 255) >= ink + 12
                        && (gray[at + flankOffset] & 255) >= ink + 12) count++;
                prefix[out] = (char) count;
            }
        }

        // Four unsigned pixels occupy separate 16-bit lanes. A guard bit prevents
        // subtraction in one lane from borrowing from its neighbor.
        private static long contrastPairs(long ink, long upper, long lower) {
            long pixels = 0x00ff00ff00ff00ffL;
            long guard = 0x8000800080008000L;
            long required = (ink & pixels) + 0x000c000c000c000cL;
            return (((upper & pixels) | guard) - required)
                    & (((lower & pixels) | guard) - required) & guard;
        }

        private static long contrastMask(
                byte[] gray, int at, int columns, int flankOffset, int threshold) {
            long mask = 0;
            int column = 0;
            if (threshold >= 127 && threshold < 255) {
                long bias = 0x0101010101010101L * (threshold + 1 - 128);
                for (; column <= columns - Long.BYTES; column += Long.BYTES, at += Long.BYTES) {
                    long word = (long) GRAY_WORDS.get(gray, at);
                    long candidates = HIGH_BITS & ~(((word | HIGH_BITS) - bias) & word);
                    if ((candidates & (candidates - 1)) != 0) {
                        long upper = (long) GRAY_WORDS.get(gray, at - flankOffset);
                        long lower = (long) GRAY_WORDS.get(gray, at + flankOffset);
                        long even = contrastPairs(word, upper, lower);
                        long odd = contrastPairs(word >>> 8, upper >>> 8, lower >>> 8);
                        long kept = candidates & ((even >>> 8) | odd);
                        // Pack the high bit of each byte into its original column position.
                        mask |= (((kept >>> 7) * 0x0102040810204080L) >>> 56) << column;
                        continue;
                    }
                    while (candidates != 0) {
                        int offset = Long.numberOfTrailingZeros(candidates) / Byte.SIZE;
                        int pixel = at + offset;
                        int ink = (int) (word >>> (offset * Byte.SIZE)) & 255;
                        if ((gray[pixel - flankOffset] & 255) >= ink + 12
                                && (gray[pixel + flankOffset] & 255) >= ink + 12)
                            mask |= 1L << (column + offset);
                        candidates &= candidates - 1;
                    }
                }
            }
            for (; column < columns; column++, at++) {
                int ink = gray[at] & 255;
                if (ink <= threshold
                        && (gray[at - flankOffset] & 255) >= ink + 12
                        && (gray[at + flankOffset] & 255) >= ink + 12) mask |= 1L << column;
            }
            return mask;
        }
    }

    private final float[][] points;
    private boolean verified;

    boolean verified() {
        return verified;
    }

    /** Sampled rule proof does not certify an extrapolated frame outside its observations. */
    boolean verifiedAt(float x) {
        return verified && x >= points[0][0] && x <= points[points.length - 1][0];
    }

    private float tailStart = Float.NEGATIVE_INFINITY,
            tailEnd = Float.POSITIVE_INFINITY,
            tailBottom,
            tailGap;

    private StaffPitchTrack(List<float[]> points) {
        this.points = points.toArray(new float[0][]);
    }

    /** Copies bounded, ordered samples after their caller has verified all five printed rules. */
    static StaffPitchTrack fromVerifiedSamples(List<float[]> samples) {
        if (samples == null || samples.size() < 2 || samples.size() > 13)
            throw new IllegalArgumentException("Invalid staff samples");
        List<float[]> copy = new ArrayList<>();
        float previous = Float.NEGATIVE_INFINITY;
        for (float[] sample : samples) {
            if (sample == null
                    || sample.length != 3
                    || !Float.isFinite(sample[0])
                    || !Float.isFinite(sample[1])
                    || !Float.isFinite(sample[2])
                    || sample[0] <= previous
                    || sample[2] < 3) throw new IllegalArgumentException("Invalid staff sample");
            copy.add(sample.clone());
            previous = sample[0];
        }
        StaffPitchTrack result = new StaffPitchTrack(copy);
        result.verified = true;
        return result;
    }

    /** Carry an already established straight staff slope into column rectification. */
    static StaffPitchTrack linear(int width, float bottom, float gap, float slope) {
        return new StaffPitchTrack(
                List.of(
                        new float[] {0, bottom - slope * width * .5f, gap},
                        new float[] {width - 1, bottom + slope * (width - 1 - width * .5f), gap}));
    }

    static StaffPitchTrack detect(
            byte[] gray, int width, int height, float top, float bottom, float gap) {
        return detect(gray, width, height, top, bottom, gap, false);
    }

    /** Symbol line removal requires sub-line tracking even when note pitch rounding is stable. */
    static StaffPitchTrack detectForSymbols(
            byte[] gray, int width, int height, float top, float bottom, float gap) {
        return detect(gray, width, height, top, bottom, gap, true);
    }

    /** A fresh raw five-rule proof for printed arcs; existing pitch tracks keep their policy. */
    static StaffPitchTrack forPrintedSlurs(
            byte[] gray, int width, int height, float top, float bottom, float gap) {
        if (width <= 0
                || height <= 0
                || gray == null
                || gray.length != (long) width * height
                || !Float.isFinite(top + bottom + gap)
                || gap < 3
                || bottom <= top)
            throw new IllegalArgumentException("Invalid printed staff source");
        // Curved detection proves sampled five-rule groups against raw ink. Its ordinary
        // straight-staff fast path returns no track, so independently prove those rules
        // with the sub-line symbol detector rather than trusting the supplied seed.
        StaffPitchTrack result = detect(gray, width, height, top, bottom, gap);
        if (result == null) result = detectForSymbols(gray, width, height, top, bottom, gap);
        if (result == null) {
            // Dense note ink can spoil small strips while all five long contrasted
            // rules still prove one flat staff. Preserve the existing scale/phase caps
            // and independently reject a sixth neighboring rule before transport.
            float[] flat = broadStraightPitch(gray, width, height, bottom, gap, false);
            if (flat != null) {
                int[] rows = new int[5];
                for (int i = 0; i < 5; i++) rows[i] = Math.round(flat[0] - (4 - i) * flat[1]);
                if (completeRules(
                        gray,
                        width,
                        height,
                        new RawStaffLineDetector.StaffLines(rows, flat[1]),
                        true))
                    result =
                            new StaffPitchTrack(
                                    List.of(
                                            new float[] {0, flat[0], flat[1]},
                                            new float[] {width - 1, flat[0], flat[1]}));
            }
        }
        if (result != null) result.verified = true;
        return result;
    }

    /** Measure geometry permits a single pixel of projection quantization; pitch tracking stays strict. */
    static StaffPitchTrack detectForMeasures(
            byte[] gray, int width, int height, float top, float bottom, float gap) {
        return detect(gray, width, height, top, bottom, gap, false, true);
    }

    private static StaffPitchTrack detect(
            byte[] gray,
            int width,
            int height,
            float top,
            float bottom,
            float gap,
            boolean subtle) {
        return detect(gray, width, height, top, bottom, gap, subtle, false);
    }

    private static StaffPitchTrack detect(
            byte[] gray,
            int width,
            int height,
            float top,
            float bottom,
            float gap,
            boolean subtle,
            boolean measures) {
        if (gray == null || gap < 3) return null;

        int stripWidth = Math.min(width, Math.max(80, Math.round(gap * 10)));
        boolean validFrame = width > 0 && height > 0 && gray.length == (long) width * height
                && Float.isFinite(top) && Float.isFinite(bottom) && Float.isFinite(gap)
                && top >= 0 && bottom < height && bottom > top;
        int minimumSamples = subtle ? 6 : 4;
        float minimumSpan = width * (subtle ? .6f : .36f);
        float lastCenter = stripLeft(width, stripWidth, 12) + stripWidth * .5f;
        float firstCenter = stripLeft(width, stripWidth, 0) + stripWidth * .5f;
        if (validFrame && lastCenter - firstCenter < minimumSpan) return null;
        if (!subtle && straightRules(gray, width, height, bottom, gap, true)) return null;
        boolean broadlyStraight = straightRules(gray, width, height, bottom, gap);
        int first = Math.max(0, Math.round(top - gap * 6)),
                last = Math.min(height, Math.round(bottom + gap * 6));
        if (last <= first) return null;
        List<float[]> samples = new ArrayList<>();
        byte[] local = subtle ? null : new byte[stripWidth * (last - first)];
        int[] symbolStrength = subtle ? new int[last - first] : null;
        int contrastFlank = Math.max(2, Math.round(gap * .32f));
        byte[] contrasted = null;
        int previousLeft = -1;
        // Sample between the broad windows too: beams can obscure one rule
        // across several windows while clear intervening ink still proves a curve.
        for (int strip = 0; strip < 13; strip++) {
            int left = stripLeft(width, stripWidth, strip);
            float center = left + stripWidth * .5f;
            // Later geometry checks can only discard points or shorten their span.
            if (validFrame && (samples.size() + 13 - strip < minimumSamples
                    || samples.isEmpty() && lastCenter - center < minimumSpan))
                return null;
            if (!subtle) copyStrip(gray, width, first, last, left, local, stripWidth);
            RawStaffLineDetector.StaffLines best = null;
            float distance = Float.MAX_VALUE;
            // A shaded photograph can put the paper itself below the fixed ink
            // threshold. Project only locally contrasted strokes in that case;
            // still validate every proposed rule against the original pixels.
            byte[] projection = local;
            if (subtle) {
                int delta = left - previousLeft;
                boolean sliding = previousLeft >= 0 && 2L * delta < stripWidth;
                if (!sliding || delta != 0) {
                    int flankOffset = contrastFlank * width;
                    for (int y = contrastFlank; y < symbolStrength.length - contrastFlank; y++) {
                        int start = (first + y) * width + left;
                        if (sliding) {
                            symbolStrength[y] += contrastCount(gray, start + stripWidth - delta, delta, flankOffset)
                                    - contrastCount(gray, start - delta, delta, flankOffset);
                        } else {
                            symbolStrength[y] = contrastCount(gray, start, stripWidth, flankOffset);
                        }
                    }
                }
                previousLeft = left;
            } else if (needsContrast(
                            local,
                            stripWidth,
                            last - first,
                            stripWidth * .5f,
                            bottom - first,
                            gap)) {
                if (contrasted == null) contrasted = new byte[local.length];
                projection = contrasted;
                Arrays.fill(projection, (byte) 255);
                int flank = contrastFlank;
                for (int y = flank; y < last - first - flank; y++)
                    for (int x = 0; x < stripWidth; x++) {
                        int at = y * stripWidth + x, ink = local[at] & 255;
                        if (ink <= 170
                                && (local[at - flank * stripWidth] & 255) >= ink + 12
                                && (local[at + flank * stripWidth] & 255) >= ink + 12)
                            projection[at] = local[at];
                    }
            }
            var candidates =
                    subtle
                            ? RawStaffLineDetector.detectFromStrength(
                                    symbolStrength,
                                    Math.max(24, Math.round(stripWidth * .25f)),
                                    last - first)
                            : RawStaffLineDetector.detect(projection, stripWidth, last - first);
            for (var lines : candidates) {
                // A four-rule semantic group can be divided into four intervals,
                // compressing its seed to about three quarters of the true gap.
                // Complete raw rules, nearby phase and broad track agreement below
                // must still independently establish any larger spacing.
                if (lines.gap() < gap * (subtle ? .88f : .8f)
                        || lines.gap() > gap * (subtle ? 1.12f : 1.45f)) continue;
                float d = Math.abs(lines.bottom() + first - bottom);
                if (subtle && d > gap * .65f) continue;
                // Broad straight-rule evidence anchors the physical staff phase.
                // A locally shifted group must be unambiguous before replacing
                // it; nearby tilted groups can safely keep their existing track.
                if (!completeRules(gray, stripWidth, last - first, lines,
                        subtle || broadlyStraight && d > gap * .6f, subtle ? 225 : 170,
                        first * width + left, width)) continue;
                // Incomplete semantic stripes can compress or widen the seed's
                // spacing. A complete raw group may recalibrate that seed only
                // while its outer rule remains close to the same physical staff.
                if ((lines.gap() < gap * .88f || lines.gap() > gap * 1.12f) && d > gap * 1.5f)
                    continue;
                if (d < distance) {
                    distance = d;
                    best = lines;
                }
            }
            if (best != null)
                samples.add(
                        new float[] {center, best.bottom() + first, best.gap()});
        }
        if (samples.size() < 4) return null;
        List<Float> gaps = new ArrayList<>();
        for (float[] sample : samples) gaps.add(sample[2]);
        float typicalGap = median(gaps);
        samples.removeIf(a -> Math.abs(a[2] - typicalGap) > typicalGap * .08f);
        if (samples.size() < 4) return null;
        List<Float> slopes = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++)
            for (int j = i + 1; j < samples.size(); j++) {
                float[] a = samples.get(i), b = samples.get(j);
                slopes.add((b[1] - a[1]) / (b[0] - a[0]));
            }
        float slope = median(slopes);
        if (Math.abs(slope) * width > typicalGap * 8 + (measures ? 1 : 0)) return null;
        List<Float> intercepts = new ArrayList<>();
        for (float[] sample : samples) intercepts.add(sample[1] - slope * sample[0]);
        float intercept = median(intercepts);
        samples.removeIf(a -> Math.abs(a[1] - intercept - slope * a[0]) > typicalGap * .8f);
        if (samples.size() < 4) return null;
        samples.sort(Comparator.comparingDouble(a -> a[0]));
        if (samples.get(samples.size() - 1)[0] - samples.get(0)[0] < width * .36f) return null;
        if (subtle
                && (samples.size() < 6
                        || samples.get(samples.size() - 1)[0] - samples.get(0)[0] < width * .6f))
            return null;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float[] p : samples) {
            min = Math.min(min, p[1]);
            max = Math.max(max, p[1]);
        }
        if (subtle && max - min > typicalGap * 1.25f) return null;
        if (max - min < typicalGap * .8f) {
            // Even a sub-line tilt can move the local search onto the adjacent
            // rule near a page edge. Accept it only with dense, broadly spaced
            // five-rule samples agreeing on a smooth trend; sparse ledger ink
            // or alternating offsets must not create a new pitch reference.
            if (!subtle && max - min < typicalGap * .4f
                    || samples.size() < 6
                    || samples.get(samples.size() - 1)[0] - samples.get(0)[0] < width * .6f)
                return null;
            float variation = 0;
            for (int i = 1; i < samples.size(); i++) {
                float change = Math.abs(samples.get(i)[1] - samples.get(i - 1)[1]);
                // Missing windows increase the distance between valid samples;
                // constrain the slope rather than treating that gap as an abrupt jump.
                float distance = samples.get(i)[0] - samples.get(i - 1)[0];
                if (change > typicalGap * .35f * distance / (width * .12f)) return null;
                variation += change;
            }
            if (variation > max - min + typicalGap * .25f) return null;
        }
        return new StaffPitchTrack(samples);
    }

    /** Recalibrate a straight semantic seed only when widely separated complete
     * printed groups agree on the same staff. Faded rules need contrast evidence,
     * not a darker global ink threshold. */
    static float[] straightPitch(
            byte[] labels, byte[] gray, int width, int height, float bottom, float gap) {
        if (labels == null || gray == null || gap < 3) return null;
        float[] broad = broadStraightPitch(gray, width, height, bottom, gap);
        if (broad != null) return broad;
        if (!straightRules(gray, width, height, bottom, gap)) return null;
        List<float[]> samples = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            float x = width * (.15f + i * .06f);
            float[] rules =
                    localRulesWithSlope(
                            labels,
                            gray,
                            width,
                            height,
                            x,
                            Math.round(x - gap * .6f),
                            Math.round(x + gap * .6f),
                            bottom,
                            gap,
                            0,
                            true);
            if (rules != null) samples.add(new float[] {x, rules[0], rules[1]});
        }
        if (samples.size() < 6
                || samples.get(samples.size() - 1)[0] - samples.get(0)[0] < width * .6f)
            return null;
        List<Float> bottoms = new ArrayList<>(), gaps = new ArrayList<>();
        for (float[] sample : samples) {
            bottoms.add(sample[1]);
            gaps.add(sample[2]);
        }
        float base = median(bottoms), spacing = median(gaps);
        for (float[] sample : samples)
            if (Math.abs(sample[1] - base) > gap * .16f
                    || Math.abs(sample[2] - spacing) > gap * .035f) return null;
        // Do not change staff phase or perturb an already accurate seed.
        if (Math.abs(base - bottom) > gap * .4f
                || Math.max(Math.abs(base - bottom), Math.abs(spacing - gap) * 8) < gap * .5f)
            return null;
        return new float[] {base, spacing};
    }

    /** Dense beams may block the small probes while each printed rule remains
     * visible across most of the page. Keep the established phase and require
     * all five thin, straight rules before correcting accumulated ledger error. */
    static float[] broadStraightPitch(byte[] gray, int width, int height, float bottom, float gap) {
        return broadStraightPitch(gray, width, height, bottom, gap, true);
    }

    static float[] broadStraightPitch(
            byte[] gray, int width, int height, float bottom, float gap, boolean correctionOnly) {
        return broadStraightPitch(gray, width, height, bottom, gap, correctionOnly, null);
    }

    // A caller probing several hints can reuse the row counts for one unchanged crop.
    // Zero denotes an unscanned row. A known count is stored as count + 1.
    static float[] broadStraightPitch(
            byte[] gray, int width, int height, float bottom, float gap, boolean correctionOnly,
            int[] rowCounts) {
        return broadStraightPitch(gray, width, height, bottom, gap, correctionOnly, rowCounts, 169, 20);
    }

    static float[] broadStraightPitch(
            byte[] gray, int width, int height, float bottom, float gap, boolean correctionOnly,
            int[] rowCounts, int darkestAllowedCenter, int minimumContrast) {
        if (gray == null || gap < 8) return null;
        float[] rows = new float[5];
        int flank = Math.max(2, Math.round(gap * .3f));
        int flankStride = flank * width;
        int radius = Math.max(2, Math.round(gap * .35f));
        int[] support = null;
        for (int line = 0; line < 5; line++) {
            int center = Math.round(bottom - line * gap),
                    first = center - radius,
                    last = center + radius;
            if (first < flank || last >= height - flank) return null;
            if (support == null) support = new int[last - first + 1];
            int peak = 0, at = 0;
            for (int y = first; y <= last; y++) {
                int count = rowCounts == null ? -1 : rowCounts[y] - 1;
                if (count < 0) {
                    count = 0;
                    int rowStart = y * width;
                    for (int pixel = rowStart; pixel < rowStart + width; pixel++) {
                        int ink = gray[pixel] & 255;
                        if (ink <= darkestAllowedCenter
                                && (gray[pixel - flankStride] & 255) > ink + minimumContrast
                                && (gray[pixel + flankStride] & 255) > ink + minimumContrast) count++;
                    }
                    if (rowCounts != null) rowCounts[y] = count + 1;
                }
                support[y - first] = count;
                if (count > peak) {
                    peak = count;
                    at = y - first;
                }
            }
            if (peak < width * .55f) return null;
            int lo = at, hi = at;
            while (lo > 0 && support[lo - 1] >= peak * .85f) lo--;
            while (hi + 1 < support.length && support[hi + 1] >= peak * .85f) hi++;
            if (hi - lo > Math.max(2, gap * .25f)) return null;
            double weight = 0, total = 0;
            for (int i = lo; i <= hi; i++) {
                weight += support[i];
                total += (first + i) * (double) support[i];
            }
            rows[line] = (float) (total / weight);
        }
        float spacing = (rows[0] - rows[4]) / 4;
        if (correctionOnly && Math.abs(spacing - gap) < gap * .035f
                || Math.abs(spacing - gap) > gap * .08f
                || Math.abs(rows[0] - bottom) > gap * .3f) return null;
        for (int i = 1; i < 5; i++)
            if (Math.abs(rows[i] - (rows[0] - spacing * i)) > gap * .12f) return null;
        return new float[] {rows[0], spacing};
    }

    private static boolean straightRules(
            byte[] gray, int width, int height, float bottom, float gap) {
        return straightRules(gray, width, height, bottom, gap, false);
    }

    private static boolean straightRules(
            byte[] gray, int width, int height, float bottom, float gap, boolean throughout) {
        int radius = Math.max(2, Math.round(gap * .3f)),
                flank = Math.max(2, Math.round(gap * .32f));
        // Broad evidence from all five original rules outweighs a few narrow
        // strips where darker beams displace a faded outer rule. Do not require
        // complete semantic labels: curved scans can lose entire labelled rules.
        for (int line = 0; line < 5; line++) {
            int supported = 0, samples = 0, row = Math.round(bottom - line * gap);
            int first = Math.round(width * .1f), last = Math.round(width * .94f);
            int[] regionSamples = new int[4], regionSupport = new int[4];
            for (int x = first; x < last; x += 2) {
                int region = Math.min(3, (x - first) * 4 / Math.max(1, last - first));
                samples++;
                regionSamples[region]++;
                for (int y = Math.max(flank, row - radius);
                        y <= Math.min(height - 1 - flank, row + radius);
                        y++) {
                    int ink = gray[y * width + x] & 255;
                    if (ink <= 205
                            && (gray[(y - flank) * width + x] & 255) >= ink + 12
                            && (gray[(y + flank) * width + x] & 255) >= ink + 12) {
                        supported++;
                        regionSupport[region]++;
                        break;
                    }
                }
            }
            if (samples < 24 || supported < samples * .8f) return false;
            // Reject a curved track only when the edge regions also support straight rules.
            // Broad scale calibration can still bridge a locally obscured group.
            if (throughout)
                for (int region = 0; region < 4; region++)
                    if (regionSamples[region] < 6
                            || regionSupport[region] < regionSamples[region] * .8f) return false;
        }
        return true;
    }

    /** Same complete five-rule/no-sixth predicate as the subtle printed-staff route. */
    static boolean completePrintedSlurRules(
            byte[] gray, int width, int height, RawStaffLineDetector.StaffLines lines) {
        return completeRules(gray, width, height, lines, true, 225);
    }

    private static boolean completeRules(
            byte[] gray,
            int width,
            int height,
            RawStaffLineDetector.StaffLines lines,
            boolean requireUnambiguousPhase) {
        return completeRules(gray, width, height, lines, requireUnambiguousPhase, 170);
    }

    private static boolean completeRules(
            byte[] gray,
            int width,
            int height,
            RawStaffLineDetector.StaffLines lines,
            boolean requireUnambiguousPhase,
            int threshold) {
        return completeRules(gray, width, height, lines, requireUnambiguousPhase, threshold, 0, width);
    }

    private static boolean completeRules(byte[] gray, int width, int height,
            RawStaffLineDetector.StaffLines lines, boolean requireUnambiguousPhase,
            int threshold, int origin, int stride) {
        int radius = Math.max(1, Math.round(lines.gap() * .2f)),
                flank = Math.max(2, Math.round(lines.gap() * .32f));
        // Six equally spaced rules do not establish which five belong to the
        // staff. A beam extending the group must not shift the sampled phase.
        if (requireUnambiguousPhase
                && (ruleHasSupport(gray, width, height, Math.round(lines.rows()[0] - lines.gap()),
                                   radius, flank, threshold, origin, stride)
                    || ruleHasSupport(gray, width, height, Math.round(lines.bottom() + lines.gap()),
                                      radius, flank, threshold, origin, stride))) return false;
        for (int row : lines.rows())
            if (!ruleHasSupport(gray, width, height, row, radius, flank, threshold, origin, stride)) return false;
        return true;
    }

    private static boolean ruleHasSupport(byte[] gray, int width, int height, int row,
                                          int radius, int flank, int threshold, int origin, int stride) {
        int first = Math.max(flank, row - radius), last = Math.min(height - 1 - flank, row + radius);
        int count = 0;
        float required = width * .6f;
        for (int x = 0; x < width;) {
            int size = Math.min(Long.SIZE, width - x);
            long supported = 0;
            for (int y = first; y <= last; y++)
                supported |= RuleProjection.contrastMask(gray, origin + y * stride + x, size, flank * stride, threshold);
            count += Long.bitCount(supported);
            x += size;
            if (count >= required) return true;
            if (count + (width - x) < required) return false;
        }
        return count >= required;
    }

    boolean activeAt(float x) {
        return x >= tailStart && x <= tailEnd;
    }

    /** Sample the staff bottom in page order without searching earlier segments again. */
    float[] columnBottoms(int width) {
        float[] bottoms = new float[width];
        int right = 1;
        for (int x = 0; x < width; x++) {
            if (x < tailStart || x > tailEnd) {
                bottoms[x] = tailBottom;
                continue;
            }
            while (right < points.length - 1 && x > points[right][0]) right++;
            float[] a = points[right - 1], b = points[right];
            float fraction = (x - a[0]) / (b[0] - a[0]);
            bottoms[x] = a[1] + fraction * (b[1] - a[1]);
        }
        return bottoms;
    }

    float[] at(float x) {
        return at(x, null);
    }

    float[] at(float x, FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        if (x < tailStart || x > tailEnd)
            return (quarterBudget == null
                            || quarterBudget.allocate(
                                    (Math.max(0L, (long) (2))) + 1,
                                    32L * 1 + 8L * (Math.max(0L, (long) (2))))
                    ? new float[] {tailBottom, tailGap}
                    : null);
        int right = 1;
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            while (right < points.length - 1 && x > points[right][0]) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                right++;
            }
        }

        float[] a = points[right - 1], b = points[right];
        float fraction = (x - a[0]) / (b[0] - a[0]);
        return (quarterBudget == null
                        || quarterBudget.allocate(
                                (Math.max(0L, (long) (2))) + 1,
                                32L * 1 + 8L * (Math.max(0L, (long) (2))))
                ? new float[] {
                    a[1] + fraction * (b[1] - a[1]),
                    a[2] + Math.max(0, Math.min(1, fraction)) * (b[2] - a[2])
                }
                : null);
    }

    /** A complete local five-line group resolves the one-line ambiguity of a partial match. */
    static boolean needsContrast(
            byte[] gray, int width, int height, float x, float bottom, float gap) {
        if (gray == null) return false;
        int left = Math.max(0, Math.round(x - gap * 4)),
                right = Math.min(width - 1, Math.round(x + gap * 4));
        int top = Math.max(0, Math.round(bottom - gap * 5)),
                last = Math.min(height - 1, Math.round(bottom + gap));
        if (left > right || top > last) return false;
        int columns = (right - left) / 2 + 1;
        int samples = columns * ((last - top) / 2 + 1);
        if (samples < 24) return false;
        float required = samples * .65f;
        int shaded = 0, remaining = samples;
        for (int y = top; y <= last; y += 2) {
            int row = y * width;
            for (int at = row + left, end = row + right; at <= end; at += 2)
                if ((gray[at] & 255) <= 205) shaded++;
            remaining -= columns;
            if (shaded >= required) return true;
            if (shaded + remaining < required) return false;
        }
        return false;
    }

    static float[] localRules(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float referenceBottom,
            float gap) {
        return localRules(
                labels, gray, width, height, x, headLeft, headRight, referenceBottom, gap, false);
    }

    static float[] localRules(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float referenceBottom,
            float gap,
            boolean curved) {
        // Curled paper can turn sharply after the final reliable wide sample.
        // Compare complete rules in a sloped window instead of extrapolating
        // the last wide-strip slope past the printed note.
        // Require page-level curvature evidence before trying inclined rules:
        // beams on a flat staff can otherwise form a competing tilted pattern.
        if (!curved) {
            return localRulesWithSlope(labels, gray, width, height, x, headLeft, headRight,
                    referenceBottom, gap, 0);
        }
        return retryLocalRules(labels, gray, width, height, x, headLeft, headRight,
                referenceBottom, gap, false, false, 205);
    }

    private static float[] retryLocalRules(
            byte[] labels, byte[] gray, int width, int height, float x, int headLeft,
            int headRight, float referenceBottom, float gap, boolean bilateral,
            boolean occluded, int threshold) {
        var projection = RETRY_PROJECTION.get();
        projection.reset();
        for (float slope : LOCAL_SLOPES) {
            float[] found =
                    localRulesWithSlope(
                            labels,
                            gray,
                            width,
                            height,
                            x,
                            headLeft,
                            headRight,
                            referenceBottom,
                            gap,
                            slope, bilateral, occluded, threshold, projection);
            if (found != null) return found;
        }
        return null;
    }

    /** White paper can curl beyond the last broad sample. Require all five rules
     * on both sides of the head before replacing that broad pitch reference. */
    static float[] localPrintedRules(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float referenceBottom,
            float gap) {
        return retryLocalRules(labels, gray, width, height, x, headLeft, headRight,
                referenceBottom, gap, true, false, 205);
    }

    /** A flat main staff can curl only after the broad sampling windows end.
     * Keep the original reference outside the independently closed tail. */
    static StaffPitchTrack closedTail(byte[] gray, int width, int height, float bottom, float gap) {
        CurvedStaffTail.Track tail =
                CurvedStaffTail.track(
                        gray, width, height, Math.round(width * .85f), bottom, gap, 0);
        if (tail == null) return null;
        StaffPitchTrack result = new StaffPitchTrack(tail.points());
        result.tailStart = tail.points().get(0)[0];
        result.tailEnd = tail.end();
        result.tailBottom = bottom;
        result.tailGap = gap;
        return result;
    }

    /** Beyond a photographed page's last reliable strip, prove five complete
     * contrasted rules on both sides, without relying on a truncated model mask. */
    static float[] localCurledEdgeRules(
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float referenceBottom,
            float gap) {
        if (gray == null || gap < 3) return null;
        int left = Math.max(0, Math.round(x - gap * 4)),
                right = Math.min(width - 1, Math.round(x + gap * 4));
        int top = Math.max(0, Math.round(referenceBottom - gap * 8)),
                bottom = Math.min(height - 1, Math.round(referenceBottom + gap * 4));
        int w = right - left + 1, h = bottom - top + 1, flank = Math.max(2, Math.round(gap * .32f));
        if (w < 8 || h < 8) return null;
        byte[] local = new byte[w * h], thin = new byte[w * h];
        for (int y = 0; y < h; y++)
            System.arraycopy(gray, (top + y) * width + left, local, y * w, w);
        for (int y = flank; y < h - flank; y++)
            for (int xx = 0; xx < w; xx++) {
                int at = y * w + xx, ink = local[at] & 255;
                if (ink <= 170
                        && (local[at - flank * w] & 255) >= ink + 20
                        && (local[at + flank * w] & 255) >= ink + 20) thin[at] = 4;
            }
        // All phase and slope trials use the same raw contrast around the head.
        float localX = x - left;
        int localHeadLeft = headLeft - left, localHeadRight = headRight - left;
        int radius = Math.max(4, Math.round(gap * 3.5f));
        int exclusion = Math.max(1, Math.round(gap * .45f));
        int band = Math.max(2, Math.round(gap * .25f));
        int center = Math.round(localX);
        var projection = new RuleProjection();
        projection.prepare(Math.max(0, center - radius), Math.min(w - 1, center + radius),
                flank, h - 1 - flank, localHeadLeft - exclusion, localHeadRight + exclusion);
        float[] best = null;
        for (int shift : new int[] {0, -1, 1, -2, 2})
            for (int s = -8; s <= 8; s++) {
                float[] found =
                        localRulesWithSlope(
                                thin,
                                local,
                                w,
                                h,
                                localX,
                                localHeadLeft,
                                localHeadRight,
                                referenceBottom - top + shift * gap,
                                gap,
                                s * .04f,
                                true, false, 205, projection);
                if (found == null) continue;
                int ruleLeft = Math.max(0, Math.round(x - left - gap * 3.5f)),
                        ruleRight = Math.min(w - 1, Math.round(x - left + gap * 3.5f));
                if (printedRuleOnBothSides(
                                local,
                                w,
                                h,
                                localX,
                                localHeadLeft,
                                localHeadRight,
                                found[0] + found[1],
                                s * .04f,
                                ruleLeft,
                                ruleRight,
                                exclusion,
                                band,
                                flank,
                                170)
                        || printedRuleOnBothSides(
                                local,
                                w,
                                h,
                                localX,
                                localHeadLeft,
                                localHeadRight,
                                found[0] - 5 * found[1],
                                s * .04f,
                                ruleLeft,
                                ruleRight,
                                exclusion,
                                band,
                                flank,
                                170)) return null;
                found[0] += top;
                // Competing phases (for example a beam plus five rules) are not proof.
                if (best != null && Math.abs(best[0] - found[0]) > gap * .3f) return null;
                if (best == null) best = found;
            }
        return best;
    }

    /** A pale local group still needs all five contrasted rules on both sides
     * and the same semantic staff support as the ordinary printed-rule path. */
    static float[] localFadedRules(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float referenceBottom,
            float gap) {
        return retryLocalRules(labels, gray, width, height, x, headLeft, headRight,
                referenceBottom, gap, true, false, 225);
    }

    /** A beam may cover one rule beside a head near the printed staff edge.
     * Keep nine independently contrasted side/rule observations and require
     * thick ink plus a staff label at the single covered observation. */
    static float[] localOccludedRules(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float referenceBottom,
            float gap) {
        return retryLocalRules(labels, gray, width, height, x, headLeft, headRight,
                referenceBottom, gap, true, true, 205);
    }

    private static float[] localRulesWithSlope(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float referenceBottom,
            float gap,
            float slope) {
        return localRulesWithSlope(
                labels,
                gray,
                width,
                height,
                x,
                headLeft,
                headRight,
                referenceBottom,
                gap,
                slope,
                false);
    }

    private static float[] localRulesWithSlope(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float referenceBottom,
            float gap,
            float slope,
            boolean bilateral) {
        return localRulesWithSlope(
                labels,
                gray,
                width,
                height,
                x,
                headLeft,
                headRight,
                referenceBottom,
                gap,
                slope,
                bilateral,
                false);
    }

    private static float[] localRulesWithSlope(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float referenceBottom,
            float gap,
            float slope,
            boolean bilateral,
            boolean occluded) {
        return localRulesWithSlope(
                labels,
                gray,
                width,
                height,
                x,
                headLeft,
                headRight,
                referenceBottom,
                gap,
                slope,
                bilateral,
                occluded,
                205);
    }

    private static float[] localRulesWithSlope(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float referenceBottom,
            float gap,
            float slope,
            boolean bilateral,
            boolean occluded,
            int inkThreshold) {
        return localRulesWithSlope(labels, gray, width, height, x, headLeft, headRight,
                referenceBottom, gap, slope, bilateral, occluded, inkThreshold, null);
    }

    private static float[] localRulesWithSlope(
            byte[] labels, byte[] gray, int width, int height, float x, int headLeft,
            int headRight, float referenceBottom, float gap, float slope, boolean bilateral,
            boolean occluded, int inkThreshold, RuleProjection projection) {
        if (gray == null) return null;
        int radius = Math.max(4, Math.round(gap * (occluded ? 2.5f : 3.5f))),
                exclusion = Math.max(1, Math.round(gap * .45f));
        int centerColumn = Math.round(x);
        int left = Math.max(0, centerColumn - radius),
                right = Math.min(width - 1, centerColumn + radius);
        int top = Math.max(0, Math.round(referenceBottom - gap * 5.5f)),
                bottom = Math.min(height - 1, Math.round(referenceBottom + gap * 1.5f));
        if (bottom < top) return null;
        int excludedLeft = headLeft - exclusion, excludedRight = headRight + exclusion;
        int excluded = Math.max(0, Math.min(right, excludedRight) - Math.max(left, excludedLeft) + 1);
        int samples = right - left + 1 - excluded;
        if (samples < 8) return null;
        // At the pale threshold, require a narrower stroke so broad shading
        // cannot masquerade as five evenly spaced printed rules.
        int flank = Math.max(2, Math.round(gap * (inkThreshold > 205 ? .22f : .32f)));
        int firstScanY = Math.max(flank, top), lastScanY = Math.min(height - 1 - flank, bottom);
        if (lastScanY - firstScanY < 4) return null;
        int strengthLength = bottom - top + 1;
        int[] strength;
        if (projection == null) {
            strength = new int[strengthLength];
        } else {
            if (projection.strength == null || projection.strength.length != strengthLength)
                projection.strength = new int[strengthLength];
            else Arrays.fill(projection.strength, 0);
            strength = projection.strength;
        }
        int flankOffset = flank * width;
        if (projection != null && !projection.prepared) {
            // The retry table reaches at most this displacement in either direction.
            int padding = (int) Math.ceil(Math.max(Math.abs(left - x), Math.abs(right - x)) * .16f);
            projection.prepare(left, right, Math.max(flank, firstScanY - padding),
                    (int) Math.min(height - 1 - flank, (long) lastScanY + padding),
                    excludedLeft, excludedRight);
        }
        // Keep the first large horizontal scan direct. A tilted retry can reuse its cache
        // across the remaining slopes.
        if (projection != null && slope != 0) projection.enableCache();
        boolean cached = projection != null && projection.hasCache();
        if (cached && slope == 0) {
            projection.projectHorizontal(gray, width, firstScanY, lastScanY, top, flankOffset,
                    inkThreshold, strength);
        } else {
            for (int xx = left; xx <= right; ) {
                if (xx >= excludedLeft && xx <= excludedRight) {
                    if (excludedRight >= right) break;
                    xx = excludedRight + 1;
                }
                int columnShift = Math.round((xx - x) * slope);
                int runEnd = xx;
                // Deskewed columns with the same shift can read contiguous raster rows.
                int spanEnd = xx < excludedLeft ? Math.min(right, excludedLeft - 1) : right;
                if (slope == 0) runEnd = spanEnd;
                else while (runEnd < spanEnd
                        && Math.round((runEnd + 1 - x) * slope) == columnShift) runEnd++;
                int start = Math.max(firstScanY, flank - columnShift);
                int end = Math.min(lastScanY, height - 1 - flank - columnShift);
                if (cached) {
                    projection.accumulate(gray, width, start, end, columnShift, top,
                            xx, runEnd, flankOffset, inkThreshold, strength);
                    xx = runEnd + 1;
                    continue;
                }
                int offset = (start + columnShift) * width + xx;
                int runWidth = runEnd - xx + 1;
                for (int y = start; y <= end; y++, offset += width) {
                    int supported = 0;
                    for (int at = offset, limit = offset + runWidth; at < limit; at++) {
                        int ink = gray[at] & 255;
                        if (ink <= inkThreshold
                                && (gray[at - flankOffset] & 255) >= ink + 12
                                && (gray[at + flankOffset] & 255) >= ink + 12) supported++;
                    }
                    strength[y - top] += supported;
                }
                xx = runEnd + 1;
            }
        }
        int
                minimum =
                        Math.max(
                                8,
                                Math.round(samples * (occluded ? .35f : bilateral ? .60f : .45f))),
                band = Math.max(2, Math.round(gap * .25f));
        // Reject beam-only peaks before selecting a five-line group, so a stronger beam
        // cannot hide a valid staff group through overlap suppression.
        int requiredSupport = (int) Math.ceil(Math.max(4, samples * (bilateral ? .45f : .2f)));
        int possibleRows = 0;
        for (int value : strength) if (value >= minimum) possibleRows++;
        if (possibleRows < 5) return null;
        int[] shifts = null;
        if (slope != 0) {
            int columns = right - left + 1;
            if (projection == null) {
                shifts = new int[columns];
            } else {
                if (projection.semanticShifts == null || projection.semanticShifts.length < columns)
                    projection.semanticShifts = new int[columns];
                shifts = projection.semanticShifts;
            }
            for (int xx = left; xx <= right; xx++) shifts[xx - left] = Math.round((xx - x) * slope);
        }
        for (int row = 0; row < strength.length; row++) {
            if (strength[row] < minimum) continue;
            int supported = 0, misses = samples - requiredSupport, center = top + row;
            columns:
            for (int xx = left; xx <= right && supported < requiredSupport; xx++) {
                if (xx >= excludedLeft && xx <= excludedRight) {
                    if (excludedRight >= right) break;
                    xx = excludedRight + 1;
                }
                int shifted = center + (shifts == null ? 0 : shifts[xx - left]);
                if (shifted >= 0 && shifted < height && labels[shifted * width + xx] == 4) {
                    supported++;
                    continue;
                }
                int start = Math.max(0, shifted - band);
                int end = Math.min(height - 1, shifted + band);
                for (int yy = start, at = start * width + xx; yy <= end; yy++, at += width) {
                    if (labels[at] != 4) continue;
                    supported++;
                    continue columns;
                }
                if (--misses < 0) break;
            }
            if (supported < requiredSupport) {
                if (--possibleRows < 5) return null;
                strength[row] = 0;
            }
        }
        float peakRadius = gap * .2f;
        float[] fitScratch = null;
        for (var lines :
                RawStaffLineDetector.detectFromStrength(strength, minimum, strength.length)) {
            if (lines.gap() < gap * .88f || lines.gap() > gap * 1.12f) continue;
            if (fitScratch == null) {
                if (projection == null) {
                    fitScratch = new float[15];
                } else {
                    if (projection.fitScratch == null) projection.fitScratch = new float[15];
                    fitScratch = projection.fitScratch;
                }
            }
            for (int i = 0; i < 5; i++) {
                int peak = lines.rows()[i], a = peak, b = peak;
                float minimumPeakStrength = strength[peak] * .85f;
                while (a > 0 && peak - a < peakRadius && strength[a - 1] >= minimumPeakStrength)
                    a--;
                while (b + 1 < strength.length
                        && b - peak < peakRadius
                        && strength[b + 1] >= minimumPeakStrength) b++;
                fitScratch[i] = top + (a + b) * .5f;
            }
            int spacingCount = 5;
            for (int i = 0; i < 5; i++)
                for (int j = i + 1; j < 5; j++)
                    fitScratch[spacingCount++] = (fitScratch[j] - fitScratch[i]) / (j - i);
            Arrays.sort(fitScratch, 5, 15);
            float spacing = fitScratch[10];
            for (int i = 0; i < 5; i++) fitScratch[i] += (4 - i) * spacing;
            Arrays.sort(fitScratch, 0, 5);
            float base = fitScratch[2];
            int consistent = 0;
            for (int i = 0; i < 5; i++) if (Math.abs(fitScratch[i] - base) <= gap * .16f) consistent++;
            // A beam and four rules can outscore a faded outer rule. If that
            // omitted rule continues on both sides at the original staff edge,
            // the shifted group is ambiguous and must not replace the reference.
            if (Math.abs(base - referenceBottom) > gap * .6f) {
                float outside = base < referenceBottom ? base + spacing : base - 5 * spacing;
                float expected =
                        base < referenceBottom ? referenceBottom : referenceBottom - 4 * gap;
                if (Math.abs(outside - expected) < gap * .35f
                        && printedRuleOnBothSides(
                                gray,
                                width,
                                height,
                                x,
                                headLeft,
                                headRight,
                                outside,
                                slope,
                                left,
                                right,
                                exclusion,
                                band,
                                flank,
                                inkThreshold)) continue;
            }
            if (occluded && Math.abs(base - referenceBottom) > gap * .45f) continue;
            if (consistent == 5
                    && Math.abs(base - referenceBottom) <= gap * 1.5f
                    && (!bilateral
                            || supportedOnBothSides(
                                    labels,
                                    gray,
                                    width,
                                    height,
                                    x,
                                    headLeft,
                                    headRight,
                                    base,
                                    spacing,
                                    slope,
                                    left,
                                    right,
                                    exclusion,
                                    band,
                                    flank,
                                    occluded,
                                    inkThreshold))) return new float[] {base, spacing};
        }
        return null;
    }

    private static boolean printedRuleOnBothSides(
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float row,
            float slope,
            int left,
            int right,
            int exclusion,
            int band,
            int flank,
            int inkThreshold) {
        int flankOffset = flank * width;
        for (int side = 0; side < 2; side++) {
            int first = Math.max(0, side == 0 ? left : headRight + exclusion + 1);
            int last = Math.min(width - 1, side == 0 ? headLeft - exclusion - 1 : right);
            int samples = last - first + 1;
            if (samples < 8) return false;
            float required = samples * .6f;
            int supported = 0;
            for (int xx = first; xx <= last && supported < required; xx++) {
                if (supported + last - xx + 1 < required) return false;
                int center = Math.round(row + (xx - x) * slope);
                int start = Math.max(flank, center - band);
                int end = Math.min(height - 1 - flank, center + band);
                for (int y = start, pixel = start * width + xx; y <= end; y++, pixel += width) {
                    int value = gray[pixel] & 255;
                    if (value <= inkThreshold
                            && (gray[pixel - flankOffset] & 255) >= value + 12
                            && (gray[pixel + flankOffset] & 255) >= value + 12) {
                        supported++;
                        break;
                    }
                }
            }
            if (supported < required) return false;
        }
        return true;
    }

    private static boolean supportedOnBothSides(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            float x,
            int headLeft,
            int headRight,
            float base,
            float gap,
            float slope,
            int left,
            int right,
            int exclusion,
            int band,
            int flank,
            boolean occluded,
            int inkThreshold) {
        int covered = 0;
        int flankOffset = flank * width, lastFlankRow = height - 1 - flank;
        int thick = Math.max(2, Math.round(gap * .25f));
        boolean horizontal = slope == 0f && Float.isFinite(x);
        for (int side = 0; side < 2; side++) {
            int first = Math.max(0, side == 0 ? left : headRight + exclusion + 1);
            int last = Math.min(width - 1, side == 0 ? headLeft - exclusion - 1 : right);
            int samples = last - first + 1;
            if (samples < 8) return false;
            float requiredInk = samples * .5f, requiredLabels = samples * .35f;
            float requiredThick = samples * .6f;
            for (int line = 0; line < 5; line++) {
                int inkColumns = 0, labelColumns = 0;
                float lineCenter = base - line * gap;
                int fixedCenter = Math.round(lineCenter);
                int fixedRow = fixedCenter * width;
                boolean probeCenter = horizontal && band >= 0
                        && fixedCenter >= flank && fixedCenter <= lastFlankRow;
                for (int xx = first; xx <= last; xx++) {
                    int remaining = last - xx + 1;
                    if (labelColumns + remaining < requiredLabels
                            || ((!occluded || covered > 0)
                                    && inkColumns + remaining < requiredInk)) return false;
                    // Once a quorum is met, only the remaining kind of evidence matters.
                    boolean ink = inkColumns >= requiredInk, label = labelColumns >= requiredLabels;
                    int center = horizontal ? fixedCenter : Math.round(lineCenter + (xx - x) * slope);
                    int start = Math.max(flank, center - band);
                    int end = Math.min(lastFlankRow, center + band);
                    if (probeCenter) {
                        int pixel = fixedRow + xx;
                        if (!ink) ink = isContrastedStaffPixel(gray, pixel, flankOffset, inkThreshold);
                        if (!label) label = labels[pixel] == 4;
                    }
                    if (!ink || !label) {
                        for (int yy = start, pixel = start * width + xx;
                                yy <= end; yy++, pixel += width) {
                            if (!ink) ink = isContrastedStaffPixel(gray, pixel, flankOffset, inkThreshold);
                            if (!label && labels[pixel] == 4) label = true;
                            if (ink && label) break;
                        }
                    }
                    if (ink) inkColumns++;
                    if (label) labelColumns++;
                    if (labelColumns >= requiredLabels && inkColumns >= requiredInk) break;
                }
                if (labelColumns < requiredLabels) return false;
                if (inkColumns >= requiredInk) continue;
                // Thick ink is only a fallback for one occluded staff side.
                if (!occluded || ++covered > 1) return false;
                int thickColumns = 0, thickOffset = thick * width;
                for (int xx = first; xx <= last && thickColumns < requiredThick; xx++) {
                    int center = Math.round(lineCenter + (xx - x) * slope);
                    int start = Math.max(thick, center - band);
                    int end = Math.min(height - 1 - thick, center + band);
                    for (int yy = start, pixel = start * width + xx;
                            yy <= end; yy++, pixel += width) {
                        if ((gray[pixel] & 255) < 140
                                && ((gray[pixel - thickOffset] & 255) < 140
                                        || (gray[pixel + thickOffset] & 255) < 140)) {
                            thickColumns++;
                            break;
                        }
                    }
                }
                if (thickColumns < requiredThick) return false;
            }
        }
        return true;
    }

    private static int stripLeft(int width, int stripWidth, int strip) {
        return Math.max(0, Math.min(width - stripWidth,
                Math.round(width * (.15f + strip * .06f) - stripWidth * .5f)));
    }

    private static void copyStrip(byte[] gray, int width, int first, int last, int left,
                                  byte[] local, int stripWidth) {
        for (int y = first; y < last; y++)
            System.arraycopy(gray, y * width + left, local, (y - first) * stripWidth, stripWidth);
    }

    private static int contrastCount(byte[] gray, int start, int length, int flank) {
        int count = 0, at = start, end = start + length;
        // Preserve scalar short-circuit reads for partial or malformed rasters.
        int wordEnd = flank >= 0 && start >= flank && end >= start && end <= (long) gray.length - flank
                ? end - Long.BYTES : -1;
        for (; at >= 0 && at <= wordEnd; at += Long.BYTES) {
            long center = (long) RuleProjection.GRAY_WORDS.get(gray, at);
            long above = (long) RuleProjection.GRAY_WORDS.get(gray, at - flank);
            if (center == above) continue;
            long below = (long) RuleProjection.GRAY_WORDS.get(gray, at + flank);
            if (center == below) continue;
            count += Long.bitCount(contrastedLanes(center, above, below)
                    | (contrastedLanes(center >>> 8, above >>> 8, below >>> 8) >>> 8));
        }
        for (; at < end; at++)
            if (isContrastedStaffPixel(gray, at, flank, 225)) count++;
        return count;
    }

    private static long contrastedLanes(long center, long above, long below) {
        // The spare high byte prevents borrows between four independent samples.
        long samples = 0x00ff00ff00ff00ffL, high = 0x8000800080008000L;
        long ink = center & samples;
        long needed = ink + 0x000c000c000c000cL;
        return high & (0x80e180e180e180e1L - ink)
                & (((above & samples) | high) - needed)
                & (((below & samples) | high) - needed);
    }

    private static boolean isContrastedStaffPixel(byte[] gray, int pixel, int flank, int threshold) {
        int value = gray[pixel] & 255;
        return value <= threshold && (gray[pixel - flank] & 255) >= value + 12
                && (gray[pixel + flank] & 255) >= value + 12;
    }

    private static float median(List<Float> values) {
        float[] sorted = new float[values.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = values.get(i);
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
