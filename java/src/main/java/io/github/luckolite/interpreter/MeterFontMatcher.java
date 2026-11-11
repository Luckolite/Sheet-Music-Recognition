// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

public final class MeterFontMatcher {
    public record Reading(String text, float topScore, float bottomScore) {}

    private final MeterGlyphMatcher matcher;

    public MeterFontMatcher(File file) throws Exception {
        this(Font.createFont(Font.TRUETYPE_FONT, file));
    }

    private MeterFontMatcher(Font font) {
        matcher =
                new MeterGlyphMatcher(
                        (glyphs, size) -> {
                            Font scaled = font.deriveFont((float) size);
                            BufferedImage scratch =
                                    new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
                            Graphics2D measure = scratch.createGraphics();
                            var bounds =
                                    scaled.createGlyphVector(measure.getFontRenderContext(), glyphs)
                                            .getPixelBounds(null, 0, 0);
                            measure.dispose();
                            int gw = bounds.width, gh = bounds.height;
                            if (gw < 1 || gh < 1)
                                return new MeterGlyphMatcher.Glyph(0, 0, new boolean[0]);
                            BufferedImage glyph =
                                    new BufferedImage(gw, gh, BufferedImage.TYPE_BYTE_GRAY);
                            Graphics2D graphics = glyph.createGraphics();
                            graphics.setColor(java.awt.Color.WHITE);
                            graphics.fillRect(0, 0, gw, gh);
                            graphics.setColor(java.awt.Color.BLACK);
                            graphics.setFont(scaled);
                            graphics.setRenderingHint(
                                    RenderingHints.KEY_TEXT_ANTIALIASING,
                                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                            graphics.drawString(glyphs, -bounds.x, -bounds.y);
                            graphics.dispose();
                            boolean[] shape = new boolean[gw * gh];
                            for (int y = 0; y < gh; y++)
                                for (int x = 0; x < gw; x++)
                                    shape[y * gw + x] = (glyph.getRGB(x, y) & 255) < 150;

                            return new MeterGlyphMatcher.Glyph(gw, gh, shape);
                        });
    }

    public Reading read(int[] p, int w, int h, int m, int f, float g) {
        return read(p, w, h, m, f, g, "", "");
    }

    public Reading read(int[] p, int w, int h, int m, int f, float g, String u, String l) {
        return read(p, w, h, m, f, g, u, l, null);
    }

    public Reading read(
            int[] p, int w, int h, int m, int f, float g, String u, String l, boolean[] ignored) {
        var r = matcher.read(p, w, h, m, f, g, u, l, ignored);
        return new Reading(r.text(), r.topScore(), r.bottomScore());
    }

    static boolean accepts(int winner, float score, float runner, String corroborated) {
        return MeterGlyphMatcher.accepts(winner, score, runner, corroborated);
    }

    private static final class Bundled {
        private static final MeterFontMatcher MATCHER = load();

        private static MeterFontMatcher load() {
            try (var font = MeterFontMatcher.class.getResourceAsStream("Bravura.otf")) {
                if (font == null) return null;
                return new MeterFontMatcher(Font.createFont(Font.TRUETYPE_FONT, font));
            } catch (FontFormatException | IOException e) {
                throw new IllegalStateException("Cannot load bundled meter font", e);
            }
        }
    }

    /** Read stacked numerals without caller-supplied OCR, preserving unique optical ownership. */
    static List<ScoreMeterChange> standaloneReadings(
            byte[] labels, byte[] gray, int width, int height,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes) {
        var choices = new TreeMap<Integer, Set<ScoreMeterChange>>();
        for (var reading : MeterChangeDetector.rasterMeterCandidates(labels, gray, width, height, measures, notes))
            choices.computeIfAbsent(reading.measureIndex(), ignored -> new HashSet<>()).add(reading);
        for (var crop : MeterChangeDetector.candidates(labels, gray, width, height, measures, notes)) {
            int measure = MeterChangeDetector.followingMeasure(crop, width, height, measures);
            if (!MeterChangeDetector.precedesNotes(crop, labels, gray, width, height, measure, measures, notes))
                continue;
            var matcher = Bundled.MATCHER;
            if (matcher == null) break;
            int cw = crop.right() - crop.left(), ch = crop.bottom() - crop.top();
            int[] pixels = new int[cw * ch];
            for (int y = 0; y < ch; y++)
                for (int x = 0; x < cw; x++) {
                    int value = gray[(crop.top() + y) * width + crop.left() + x] & 255;
                    pixels[y * cw + x] = 0xff000000 | value << 16 | value << 8 | value;
                }
            MeterCropRaster.prepare(pixels, cw, ch, crop.top(), crop.firstLine(), crop.gap(), false);
            var font = matcher.read(pixels, cw, ch,
                    Math.round(crop.firstLine() + 2 * crop.gap()) - crop.top(),
                    crop.firstLine() - crop.top(), crop.gap());
            String supported = MusicalOcrEvidence.fontMeter(
                    font.text(), font.topScore(), font.bottomScore(), Set.of(), Set.of());
            if (supported.isBlank()) continue;
            String[] digits = supported.split("/");
            var reading = new ScoreMeterChange(measure, Integer.parseInt(digits[0]), Integer.parseInt(digits[1]));
            choices.computeIfAbsent(measure, ignored -> new HashSet<>()).add(reading);
        }
        return choices.values().stream().filter(readings -> readings.size() == 1)
                .flatMap(Set::stream).toList();
    }
}
