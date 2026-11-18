// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Conservative fallback for stacked music-font numerals that ordinary text OCR omits. */
public final class MeterGlyphMatcher {
    public record Reading(String text, float topScore, float bottomScore) {}

    private record Match(int value, float score) {}

    private record Ranking(Match winner, float runnerScore) {}

    public record Glyph(int width, int height, boolean[] ink) {}

    @FunctionalInterface
    public interface Rasterizer {
        Glyph render(String glyphs, int size);
    }

    private static final int[] NUMERATORS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final int[] DENOMINATORS = {2, 4, 8, 16, 32};
    private static final int[] CORE_THRESHOLDS = {60, 75, 90};
    private static final float[] COMPACT_RATIOS = {.8f, .9f};

    private static final String[] GLYPH_TEXT = new String[33];
    static {
        for (int value = 1; value < GLYPH_TEXT.length; value++) {
            char last = (char) (0xe080 + value % 10);
            GLYPH_TEXT[value] = value < 10
                    ? String.valueOf(last)
                    : new String(new char[] {(char) (0xe080 + value / 10), last});
        }
    }

    private final Rasterizer rasterizer;

    public MeterGlyphMatcher(Rasterizer rasterizer) {
        this.rasterizer = java.util.Objects.requireNonNull(rasterizer);
    }

    public Reading read(int[] pixels, int width, int height, int middle, int firstLine, float gap) {
        return read(pixels, width, height, middle, firstLine, gap, "", "");
    }

    public Reading read(
            int[] pixels,
            int width,
            int height,
            int middle,
            int firstLine,
            float gap,
            String upperEvidence,
            String lowerEvidence) {
        return read(
                pixels, width, height, middle, firstLine, gap, upperEvidence, lowerEvidence, null);
    }

    public Reading read(
            int[] pixels,
            int width,
            int height,
            int middle,
            int firstLine,
            float gap,
            String upperEvidence,
            String lowerEvidence,
            boolean[] ignored) {
        Reading original =
                readStandard(
                        pixels,
                        width,
                        height,
                        middle,
                        firstLine,
                        gap,
                        upperEvidence,
                        lowerEvidence,
                        ignored);
        if (!original.text().isBlank()
                || original.topScore() < .65f
                || original.bottomScore() < .65f) return original;
        Reading accepted = null;
        int[] core = new int[pixels.length];
        for (int threshold : CORE_THRESHOLDS) {
            for (int i = 0; i < core.length; i++)
                core[i] = (pixels[i] & 255) < threshold ? 0xff000000 : 0xffffffff;
            Reading retry =
                    readStandard(core, width, height, middle, firstLine, gap, "", "", ignored);
            if (retry.text().isBlank()
                    || (accepted != null && !accepted.text().equals(retry.text()))) return original;
            if (accepted == null
                    || Math.min(retry.topScore(), retry.bottomScore())
                            < Math.min(accepted.topScore(), accepted.bottomScore()))
                accepted = retry;
        }
        String[] digits = accepted.text().split("/");
        if ((!upperEvidence.isBlank() && !upperEvidence.equals(digits[0]))
                || (!lowerEvidence.isBlank() && !lowerEvidence.equals(digits[1]))) return original;
        return accepted;
    }

    private Reading readStandard(
            int[] pixels,
            int width,
            int height,
            int middle,
            int firstLine,
            float gap,
            String upperEvidence,
            String lowerEvidence,
            boolean[] ignored) {
        Reading original =
                readAt(
                        pixels,
                        width,
                        height,
                        middle,
                        firstLine,
                        gap,
                        upperEvidence,
                        lowerEvidence,
                        ignored);
        if (!original.text().isBlank()
                || !upperEvidence.isBlank()
                || !lowerEvidence.isBlank()
                || !Float.isFinite(gap)
                || gap < 6) return original;
        // Engraved numerals may sit below or above the staff midpoint. Keep the
        // physical rail positions fixed while testing nearby digit boundaries.
        Reading accepted = null;
        int agreeing = 0;
        int step = Math.max(1, Math.round(gap * .14f));
        int radius = Math.round(gap * .8f);
        for (int offset = -radius; offset <= radius; offset += step) {
            if (Math.abs(offset) < step) continue;
            Reading retry =
                    readAt(pixels, width, height, middle + offset, firstLine, gap, "", "", ignored);
            if (retry.text().isBlank()) continue;
            if (accepted != null && !accepted.text().equals(retry.text())) return original;
            if (accepted == null
                    || Math.min(retry.topScore(), retry.bottomScore())
                            > Math.min(accepted.topScore(), accepted.bottomScore()))
                accepted = retry;
            agreeing++;
        }
        return agreeing >= 2 ? accepted : original;
    }

    private Reading readAt(
            int[] pixels,
            int width,
            int height,
            int middle,
            int firstLine,
            float gap,
            String upperEvidence,
            String lowerEvidence,
            boolean[] ignored) {
        if (ignored != null && ignored.length != pixels.length)
            throw new IllegalArgumentException("Meter exclusion size");
        if (width < gap * 1.35f || height < 12 || middle < 6 || middle > height - 6)
            return new Reading("", 0, 0);
        Match top =
                match(
                        pixels,
                        width,
                        height,
                        0,
                        middle,
                        firstLine,
                        gap,
                        NUMERATORS,
                        upperEvidence,
                        ignored);
        Match bottom =
                match(
                        pixels,
                        width,
                        height,
                        middle,
                        height,
                        firstLine,
                        gap,
                        DENOMINATORS,
                        lowerEvidence,
                        ignored);
        if (top.value() == 0 || bottom.value() == 0)
            return new Reading("", top.score(), bottom.score());
        return new Reading(top.value() + "/" + bottom.value(), top.score(), bottom.score());
    }

    private Match match(
            int[] pixels,
            int width,
            int height,
            int first,
            int last,
            int firstLine,
            float gap,
            int[] values,
            String corroborated,
            boolean[] ignored) {
        var normal =
                matches(
                        pixels,
                        width,
                        height,
                        first,
                        last,
                        firstLine,
                        gap,
                        values,
                        corroborated,
                        ignored,
                        false);
        Match winner = normal.winner();
        if (winner.value() == 0) return winner;
        if (accepts(winner.value(), winner.score(), normal.runnerScore(), corroborated))
            return winner;
        if (winner.score() >= .80f) {
            var weighted =
                    matches(
                            pixels,
                            width,
                            height,
                            first,
                            last,
                            firstLine,
                            gap,
                            values,
                            corroborated,
                            ignored,
                            true);
            Match retry = weighted.winner();
            if (retry.value() == winner.value()
                    && retry.score() >= winner.score() + .05f
                    && accepts(retry.value(), retry.score(), weighted.runnerScore(), corroborated))
                return retry;
        }
        // A narrow engraving can preserve the winning digit while missing a uniform-size template.
        // Keep the original identity and confidence/separation requirements for this bounded retry.
        if (winner.score() >= .80f) {
            Match compactBest = null;
            for (float ratio : COMPACT_RATIOS) {
                var compact =
                        matches(
                                pixels,
                                width,
                                height,
                                first,
                                last,
                                firstLine,
                                gap,
                                values,
                                corroborated,
                                ignored,
                                false,
                                ratio);
                Match retry = compact.winner();
                if (retry.value() != winner.value()
                        || retry.score() < winner.score() + .03f
                        || !accepts(
                                retry.value(), retry.score(), compact.runnerScore(), corroborated))
                    continue;
                if (compactBest == null || retry.score() > compactBest.score()) compactBest = retry;
            }
            if (compactBest != null) return compactBest;
        }
        return new Match(0, winner.score());
    }

    private Ranking matches(
            int[] pixels,
            int width,
            int height,
            int first,
            int last,
            int firstLine,
            float gap,
            int[] values,
            String corroborated,
            boolean[] ignored,
            boolean bold) {
        return matches(
                pixels,
                width,
                height,
                first,
                last,
                firstLine,
                gap,
                values,
                corroborated,
                ignored,
                bold,
                1f);
    }

    private Ranking matches(
            int[] pixels,
            int width,
            int height,
            int first,
            int last,
            int firstLine,
            float gap,
            int[] values,
            String corroborated,
            boolean[] ignored,
            boolean bold,
            float widthRatio) {
        int h = last - first;
        // Row prefix counts score each translated ink run with two differences.
        int stride = width + 1;
        int[] source = new int[stride * h], valid = new int[source.length];
        int sourceCount = 0;
        int railRadius = Math.max(1, Math.round(gap * .12f));
        int[] rails = new int[5];
        for (int n = 0; n < rails.length; n++) rails[n] = Math.round(firstLine + n * gap);
        for (int y = 0, dest = 0, page = first * width; y < h;
                y++, dest += stride, page += width) {
            boolean onLine = false;
            for (int rail : rails) {
                if (Math.abs(first + y - rail) <= railRadius) {
                    onLine = true;
                    break;
                }
            }
            if (onLine) continue;
            int rowInk = 0, rowValid = 0;
            for (int x = 0; x < width; x++) {
                if (ignored == null || !ignored[page + x]) {
                    rowValid++;
                    if ((pixels[page + x] & 255) < 150) rowInk++;
                }
                source[dest + x + 1] = rowInk;
                valid[dest + x + 1] = rowValid;
            }
            sourceCount += rowInk;
        }
        if (sourceCount < gap * 3) return new Ranking(new Match(0, 0), 0);
        long[] sourceBits = width <= Long.SIZE ? packedRows(source, width, h) : null;
        long[] validBits = width <= Long.SIZE ? packedRows(valid, width, h) : null;
        int winner = 0;
        float winningScore = -1, runnerScore = 0;
        int minSize = Math.round(gap * 2.1f), maxSize = Math.round(gap * 5.15f);
        int sizeStep = Math.max(2, Math.round(gap * .18f));
        for (int value : values) {
            float best = 0;
            String glyphs = GLYPH_TEXT[value];
            for (int size = minSize; size <= maxSize; size += sizeStep) {
                Glyph glyph = rasterizer.render(glyphs, size);
                if (widthRatio != 1f) glyph = widthScaled(glyph, widthRatio);
                if (bold) glyph = weighted(glyph);
                int gw = glyph.width(), gh = glyph.height();
                if (gw < 1 || gh < 1 || gw > width + 8 || gh > h + 8) continue;
                if (sourceBits != null && gw <= Long.SIZE) {
                    best = Math.max(best, bestPacked(glyph, width, sourceCount, sourceBits, validBits));
                    continue;
                }
                var runs = GlyphRuns.from(glyph);
                int[] rowRuns = runs.rows(), bounds = runs.bounds();
                for (int dx = -4; dx <= width - gw + 4; dx++) {
                    for (int dy = -4; dy <= h - gh + 4; dy++) {
                        int tp = 0, targetCount = 0;
                        int gyStart = Math.max(0, -dy), gyEnd = Math.min(gh, h - dy);
                        int row = (gyStart + dy) * stride;
                        for (int gy = gyStart; gy < gyEnd; gy++, row += stride) {
                            if (valid[row + width] == 0) continue;
                            for (int i = rowRuns[gy], end = rowRuns[gy + 1]; i < end; i += 2) {
                                int left = Math.max(0, dx + bounds[i]);
                                int right = Math.min(width, dx + bounds[i + 1]);
                                if (left >= right) continue;
                                tp += source[row + right] - source[row + left];
                                targetCount += valid[row + right] - valid[row + left];
                            }
                        }
                        float score = 2f * tp / Math.max(1, sourceCount + targetCount);
                        if (score > best) best = score;
                    }
                }
            }
            if (best > winningScore) {
                runnerScore = Math.max(0, winningScore);
                winner = value;
                winningScore = best;
            } else if (best > runnerScore) {
                runnerScore = best;
            }
        }
        return new Ranking(new Match(winner, winningScore), runnerScore);
    }

    private static long[] packedRows(int[] prefix, int width, int height) {
        long[] bits = new long[height];
        for (int y = 0, row = 0; y < height; y++, row += width + 1) {
            for (int x = 0; x < width; x++) {
                if (prefix[row + x] != prefix[row + x + 1]) bits[y] |= 1L << x;
            }
        }
        return bits;
    }

    static float bestPacked(
            Glyph glyph, int width, int sourceCount, long[] source, long[] valid) {
        int gw = glyph.width(), gh = glyph.height(), height = source.length;
        long[] rows = new long[gh], shifted = new long[gh];
        for (int y = 0, i = 0; y < gh; y++) {
            long bits = 0;
            for (int x = 0; x < gw; x++, i++) {
                if (glyph.ink()[i]) bits |= 1L << x;
            }
            rows[y] = bits;
        }
        float best = 0;
        for (int dx = -4; dx <= Math.min(width - 1, width - gw + 4); dx++) {
            for (int y = 0; y < gh; y++) {
                shifted[y] = dx < 0 ? rows[y] >>> -dx : rows[y] << dx;
            }
            for (int dy = -4; dy <= height - gh + 4; dy++) {
                int tp = 0, targetCount = 0;
                int end = Math.min(gh, height - dy);
                for (int y = Math.max(0, -dy); y < end; y++) {
                    tp += Long.bitCount(shifted[y] & source[y + dy]);
                    targetCount += Long.bitCount(shifted[y] & valid[y + dy]);
                }
                best = Math.max(best, 2f * tp / Math.max(1, sourceCount + targetCount));
            }
        }
        return best;
    }

    private record GlyphRuns(int[] rows, int[] bounds) {
        static GlyphRuns from(Glyph glyph) {
            int width = glyph.width(), height = glyph.height();
            boolean[] ink = glyph.ink();
            int[] rows = new int[height + 1];
            int[] bounds = new int[(width + (width & 1)) * height];
            int count = 0;
            for (int y = 0; y < height; y++) {
                int row = y * width, x = 0;
                while (x < width) {
                    if (!ink[row + x]) {
                        x++;
                        continue;
                    }
                    bounds[count++] = x++;
                    while (x < width && ink[row + x]) x++;
                    bounds[count++] = x;
                }
                rows[y + 1] = count;
            }
            return new GlyphRuns(rows, bounds);
        }
    }

    private static Glyph widthScaled(Glyph glyph, float ratio) {
        int w = Math.max(1, Math.round(glyph.width() * ratio)), h = glyph.height();
        if (glyph.width() < 1 || h < 1) return glyph;
        boolean[] ink = new boolean[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                ink[y * w + x] =
                        glyph.ink()[
                                y * glyph.width()
                                        + Math.min(
                                                glyph.width() - 1,
                                                (int) Math.floor((x + .5) * glyph.width() / w))];
        return new Glyph(w, h, ink);
    }

    /** One-pixel orthogonal stroke expansion models heavier printed ink. */
    private static Glyph weighted(Glyph glyph) {
        int w = glyph.width() + 2, h = glyph.height() + 2;
        boolean[] ink = new boolean[w * h];
        for (int y = 0; y < glyph.height(); y++)
            for (int x = 0; x < glyph.width(); x++)
                if (glyph.ink()[y * glyph.width() + x])
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++)
                            if (Math.abs(dx) + Math.abs(dy) <= 1)
                                ink[(y + 1 + dy) * w + x + 1 + dx] = true;
        return new Glyph(w, h, ink);
    }

    static boolean accepts(int winner, float score, float runnerUp, String corroborated) {
        return score >= .84f
                && (score - runnerUp >= .05f || Integer.toString(winner).equals(corroborated));
    }
}
