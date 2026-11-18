// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.util.function.Supplier;

/** Platform-independent ornament templates and bilinear grayscale comparison. */
final class PortableOrnamentGlyphs {
    static final int W = 48, H = 40;
    private static final Match NO_MATCH = new Match(0, 0, 0);

    private record Template(int kind, float aspect, float[] mask) {}

    record Match(int kind, float score, float margin) {
        boolean accepted() {
            return kind != 0 && score >= .50f && margin >= .07f
                    || kind == NoteOrnament.TRILL && score >= .44f && margin >= .18f;
        }
    }

    /** One internal read-only raster query; never retain it across a caller operation. */
    static final class Query {
        private byte[] gray;
        private int width, left, top, right, bottom;
        private float[] pixels;
        private float aspect;
    }

    private final List<Template> ornaments = new ArrayList<>(), accidentals = new ArrayList<>();

    private final Supplier<PortableOrnamentGlyphs> source;

    PortableOrnamentGlyphs() {
        this(null);
    }

    /** The desktop holder builds its font and PNG templates on the first glyph query. */
    PortableOrnamentGlyphs(Supplier<PortableOrnamentGlyphs> source) {
        this.source = source;
    }

    private List<Template> templates(boolean accidental) {
        var bank = source == null ? this : source.get();
        return accidental ? bank.accidentals : bank.ornaments;
    }

    void add(byte[] gray, int width, int height, int kind, boolean accidental) {
        if (gray == null || width < 1 || height < 1 || gray.length != (long) width * height)
            throw new IllegalArgumentException("Invalid glyph raster");
        float[] pixels = recognitionMask(gray, width, 0, 0, width, height);
        float aspect = width / (float) height;
        List<Template> list = templates(accidental);
        list.add(new Template(kind, aspect, pixels));
        if (!accidental && (kind == NoteOrnament.MORDENT || kind == NoteOrnament.INVERTED_MORDENT))
            for (float factor : new float[] {.6f, .75f, .9f})
                list.add(new Template(kind, aspect * factor, pixels));
    }

    Match match(byte[] gray, int width, PortableNoteOrnaments.Bounds bounds) {
        return match(gray, width, bounds, (Query) null);
    }

    /** Internal single-call sharing; ornament recovery still evaluates each sample normally. */
    Match match(byte[] gray, int width, PortableNoteOrnaments.Bounds bounds, Query query) {
        Match original = match(gray, width, bounds, templates(false), query, null);
        if (original.accepted()
                || templates(false).isEmpty()
                || !MordentContour.matches(gray, width, bounds)) return original;
        return recoverMordent(gray, width, bounds, original);
    }

    Match matchWithMordentContour(byte[] gray, int width, PortableNoteOrnaments.Bounds bounds) {
        Match original = templateMatch(gray, width, bounds);
        return original.accepted() || templates(false).isEmpty()
                ? original : recoverMordent(gray, width, bounds, original);
    }

    private Match recoverMordent(
            byte[] gray, int width, PortableNoteOrnaments.Bounds bounds, Match original) {
        int w = bounds.width(), h = bounds.height();
        int[] tones = new int[256];
        for (int y = bounds.top; y < bounds.bottom; y++) {
            int row = y * width;
            for (int at = row + bounds.left, end = row + bounds.right; at < end; at++)
                tones[gray[at] & 255]++;
        }
        int low = percentile(tones, w * h, .1), high = percentile(tones, w * h, .95);
        if (high - low < 60) return original;
        // Each present tone has the same normalized value throughout the crop.
        for (int tone = 0; tone < tones.length; tone++) {
            if (tones[tone] > 0)
                tones[tone] = Math.max(0, Math.min(255, Math.round((tone - low) * 255f / (high - low))));
        }
        byte[] normalized = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int source = (bounds.top + y) * width + bounds.left;
            int dest = y * w;
            for (int x = 0; x < w; x++) normalized[dest + x] = (byte) tones[gray[source + x] & 255];
        }
        Match recovered = match(normalized, w, new PortableNoteOrnaments.Bounds(0, 0, w, h),
                templates(false), null, COMPACT_MORDENT);
        return recovered.kind() == NoteOrnament.MORDENT && recovered.accepted()
                ? recovered
                : original;
    }

    /** Template-only comparison for other glyph families; excludes ornament-specific recovery. */
    Match templateMatch(byte[] gray, int width, PortableNoteOrnaments.Bounds bounds) {
        return templateMatch(gray, width, bounds, null);
    }

    Match templateMatch(byte[] gray, int width, PortableNoteOrnaments.Bounds bounds, Query query) {
        return match(gray, width, bounds, templates(false), query, null);
    }

    private static int percentile(int[] tones, int count, double fraction) {
        int total = 0;
        double target = count * fraction;
        for (int value = 0; value < tones.length; value++) {
            total += tones[value];
            if (total >= target) return value;
        }
        return 255;
    }

    private static final Template COMPACT_MORDENT = compactMordent();

    /** Original procedural two-cycle stroke for compact, optically heavy printing. */
    private static Template compactMordent() {
        int w = 44, h = 24;
        byte[] gray = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double center = 11.5 - 6 * Math.sin(4 * Math.PI * x / (w - 1));
                double ink = Math.max(0, Math.min(1, 7.5 - Math.abs(y - center)));
                gray[y * w + x] = (byte) Math.round(255 * (1 - ink));
            }
        return new Template(
                NoteOrnament.MORDENT, w / (float) h, recognitionMask(gray, w, 0, 0, w, h));
    }

    Match accidental(byte[] gray, int width, PortableNoteOrnaments.Bounds bounds) {
        return match(gray, width, bounds, templates(true));
    }

    private Match match(
            byte[] gray, int width, PortableNoteOrnaments.Bounds bounds, List<Template> choices) {
        return match(gray, width, bounds, choices, null, null);
    }

    private Match match(
            byte[] gray,
            int width,
            PortableNoteOrnaments.Bounds bounds,
            List<Template> choices,
            Query query,
            Template recoveryTemplate) {
        if (gray == null
                || width < 1
                || bounds.left < 0
                || bounds.top < 0
                || bounds.right > width
                || bounds.bottom > gray.length / width
                || bounds.width() < 1
                || bounds.height() < 1) return NO_MATCH;
        float[] candidate = null;
        float aspect;
        if (query != null) {
            if (query.gray != gray
                    || query.width != width
                    || query.left != bounds.left
                    || query.top != bounds.top
                    || query.right != bounds.right
                    || query.bottom != bounds.bottom) {
                query.pixels = null;
                query.aspect = bounds.width() / (float) bounds.height();
                query.gray = gray;
                query.width = width;
                query.left = bounds.left;
                query.top = bounds.top;
                query.right = bounds.right;
                query.bottom = bounds.bottom;
            }
            candidate = query.pixels;
            aspect = query.aspect;
        } else {
            aspect = bounds.width() / (float) bounds.height();
        }
        Map<Integer, Float> scores = null;
        float[] scoredMask = null;
        float maskScore = 0;
        int choiceCount = choices.size();
        for (int i = 0; i < choiceCount + (recoveryTemplate == null ? 0 : 1); i++) {
            Template t = i < choiceCount ? choices.get(i) : recoveryTemplate;
            float relativeAspect = aspect / t.aspect;
            // These outer bounds sit beyond both exact logarithmic rejection limits.
            if (relativeAspect < .6f || relativeAspect > 1.6f) continue;
            double ratio = Math.abs(Math.log(relativeAspect));
            if (ratio > .45) continue;
            if (candidate == null) {
                candidate = recognitionMask(
                        gray, width, bounds.left, bounds.top, bounds.right, bounds.bottom);
                if (query != null) query.pixels = candidate;
            }
            // Aspect variants of one template share their original mask.
            if (t.mask != scoredMask) {
                scoredMask = t.mask;
                maskScore = overlapScore(candidate, scoredMask);
            }
            if (scores == null) scores = new HashMap<>();
            scores.merge(t.kind, maskScore - (float) ratio * .20f, Math::max);
        }
        if (scores == null) return NO_MATCH;
        int kind = 0;
        float best = 0, second = 0;
        for (var e : scores.entrySet())
            if (e.getValue() > best) {
                second = best;
                best = e.getValue();
                kind = e.getKey();
            } else second = Math.max(second, e.getValue());
        return new Match(kind, best, best - second);
    }

    private static float overlapScore(float[] candidate, float[] reference) {
        float overlap = 0, total = 0;
        // Masks contain finite values. Keep both sums in the original sample order.
        for (int i = 0; i < candidate.length; i++) {
            float value = candidate[i], expected = reference[i];
            if (value < expected) {
                overlap += value;
                total += expected;
            } else {
                overlap += expected;
                total += value;
            }
        }
        return overlap / Math.max(.001f, total);
    }

    private static float[] sampledLuminance(
            byte[] gray, int width, int left, int top, int right, int bottom) {
        return sampledLuminance(gray, width, left, top, right, bottom, null);
    }

    private static float[] sampledLuminance(
            byte[] gray, int width, int left, int top, int right, int bottom, int[] tones) {
        float[] values = new float[W * H];
        int[] xStarts = new int[W], xEnds = new int[W];
        float[] xFractions = new float[W], leftWeights = new float[W];
        for (int x = 0; x < W; x++) {
            float sx = left + (x + .5f) * (right - left) / W - .5f;
            int x0 = Math.max(left, Math.min(right - 1, (int) Math.floor(sx)));
            xStarts[x] = x0;
            xEnds[x] = Math.min(right - 1, x0 + 1);
            float fraction = Math.max(0, Math.min(1, sx - x0));
            xFractions[x] = fraction;
            leftWeights[x] = 1 - fraction;
        }
        for (int y = 0; y < H; y++) {
            float sy = top + (y + .5f) * (bottom - top) / H - .5f;
            int y0 = Math.max(top, Math.min(bottom - 1, (int) Math.floor(sy)));
            int y1 = Math.min(bottom - 1, y0 + 1);
            float fy = Math.max(0, Math.min(1, sy - y0));
            float upperWeight = 1 - fy;
            int upperRow = y0 * width, lowerRow = y1 * width;
            int dest = y * W;
            for (int x = 0; x < W; x++) {
                int x0 = xStarts[x], x1 = xEnds[x];
                float fx = xFractions[x], leftWeight = leftWeights[x];
                float shade =
                        (gray[upperRow + x0] & 255) * leftWeight * upperWeight
                                + (gray[upperRow + x1] & 255) * fx * upperWeight
                                + (gray[lowerRow + x0] & 255) * leftWeight * fy
                                + (gray[lowerRow + x1] & 255) * fx * fy;
                values[dest + x] = shade;
                if (tones != null) tones[Math.max(0, Math.min(255, Math.round(shade)))]++;
            }
        }
        return values;
    }

    /** Raw unsigned bilinear ink accessor; each call rereads the caller's original raster. */
    private static float[] mask(byte[] gray, int width, int left, int top, int right, int bottom) {
        float[] values = sampledLuminance(gray, width, left, top, right, bottom);
        for (int i = 0; i < values.length; i++) values[i] = 1 - values[i] / 255f;
        return values;
    }

    /** Contrast normalization is confined to template construction and recognition queries. */
    private static float[] recognitionMask(
            byte[] gray, int width, int left, int top, int right, int bottom) {
        int[] tones = new int[256];
        float[] values = sampledLuminance(gray, width, left, top, right, bottom, tones);
        int paper = percentile(tones, values.length, .85);
        // Preserve established bright-paper templates and solid dark glyph queries.
        if (paper < 96 || paper >= 240) {
            for (int i = 0; i < values.length; i++) values[i] = 1 - values[i] / 255f;
            return values;
        }
        GlyphInkContrast.maskInPlace(values, tones);
        return values;
    }
}
