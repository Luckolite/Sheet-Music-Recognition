// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class MeterFontMatcher {
    public record Reading(String text, float topScore, float bottomScore) {}
    private record FontKey(String glyphs, int size) {}

    private static final class GrayRaster {
        private static final FontRenderContext MEASURE_CONTEXT;
        private static final boolean[] INK = new boolean[256];

        static {
            var image = new BufferedImage(256, 1, BufferedImage.TYPE_BYTE_GRAY);
            var graphics = image.createGraphics();
            MEASURE_CONTEXT = graphics.getFontRenderContext();
            graphics.dispose();
            // Map every stored sample through the same RGB conversion as the drawn glyph.
            byte[] samples = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < samples.length; i++) samples[i] = (byte) i;
            int[] colors = image.getRGB(0, 0, 256, 1, null, 0, 256);
            for (int i = 0; i < colors.length; i++) INK[i] = (colors[i] & 255) < 150;
        }
    }

    private final MeterGlyphMatcher matcher;

    public MeterFontMatcher(File file) throws Exception {
        this(Font.createFont(Font.TRUETYPE_FONT, file));
    }

    private MeterFontMatcher(Font font) {
        this(fontRasterizer(font));
    }

    private MeterFontMatcher(MeterGlyphMatcher.Rasterizer rasterizer) {
        matcher = new MeterGlyphMatcher(rasterizer);
    }

    static MeterGlyphMatcher.Rasterizer fontRasterizer(Font font) {
        // Reuse small numeral masks across numerator, denominator and crop retries.
        var masks =
                Collections.synchronizedMap(
                        new LinkedHashMap<FontKey, MeterGlyphMatcher.Glyph>(256, .75f, true) {
                            @Override
                            protected boolean removeEldestEntry(
                                    Map.Entry<FontKey, MeterGlyphMatcher.Glyph> eldest) {
                                return size() > 512;
                            }
                        });
        return (glyphs, size) -> {
            var key = new FontKey(glyphs, size);
            var cached = masks.get(key);
            if (cached != null) return cached;
            Font scaled = font.deriveFont((float) size);
            var bounds =
                    scaled.createGlyphVector(GrayRaster.MEASURE_CONTEXT, glyphs)
                            .getPixelBounds(null, 0, 0);
            int gw = bounds.width, gh = bounds.height;
            if (gw < 1 || gh < 1) return new MeterGlyphMatcher.Glyph(0, 0, new boolean[0]);
            BufferedImage glyph = new BufferedImage(gw, gh, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D graphics = glyph.createGraphics();
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, gw, gh);
            graphics.setColor(java.awt.Color.BLACK);
            graphics.setFont(scaled);
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawString(glyphs, -bounds.x, -bounds.y);
            graphics.dispose();
            boolean[] shape = new boolean[gw * gh];
            var buffer = (DataBufferByte) glyph.getRaster().getDataBuffer();
            byte[] samples = buffer.getData();
            for (int i = 0; i < shape.length; i++) shape[i] = GrayRaster.INK[samples[i] & 255];

            var result = new MeterGlyphMatcher.Glyph(gw, gh, shape);
            if (shape.length <= 4096) masks.put(key, result);
            return result;
        };
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
            if (MeterFontMatcher.class.getResource("Bravura.otf") == null) return null;
            var masks = MeterGlyphAtlas.load();
            if (masks == null) return new MeterFontMatcher(BundledFont.RASTERIZER);
            return new MeterFontMatcher(
                    (glyphs, size) -> {
                        var mask = masks.render(glyphs, size);
                        return mask != null ? mask : BundledFont.RASTERIZER.render(glyphs, size);
                    });
        }
    }

    private static final class BundledFont {
        private static final MeterGlyphMatcher.Rasterizer RASTERIZER = load();

        private static MeterGlyphMatcher.Rasterizer load() {
            try (var font = MeterFontMatcher.class.getResourceAsStream("Bravura.otf")) {
                return fontRasterizer(Font.createFont(Font.TRUETYPE_FONT, font));
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
        var serifChoices = new TreeMap<Integer, Set<ScoreMeterChange>>();
        var page = MeterChangeDetector.pageCandidates(labels, gray, width, height, measures, notes);
        for (var reading : page.readings())
            choices.computeIfAbsent(reading.measureIndex(), ignored -> new HashSet<>()).add(reading);
        for (var crop : page.crops()) {
            int measure = MeterChangeDetector.followingMeasure(crop, width, height, measures);
            if (!MeterChangeDetector.precedesNotes(crop, labels, gray, width, height, measure, measures, notes))
                continue;
            String supported = supportedMeter(readCrop(gray, width, height, crop));
            if (supported.isBlank()) {
                var trimmed = MeterDoubleRuleCrop.trim(
                        crop, gray, width, height, measures.get(measure).left() * width);
                if (trimmed != crop
                        && MeterChangeDetector.followingMeasure(trimmed, width, height, measures) == measure
                        && MeterChangeDetector.precedesNotes(
                                trimmed, labels, gray, width, height, measure, measures, notes))
                    supported = supportedMeter(readCrop(gray, width, height, trimmed));
            }
            if (supported.isBlank() && !choices.containsKey(measure)) {
                var cropped =
                        TerminalTripleMeter.crop(
                                crop, labels, gray, width, height, measure, measures, notes);
                if (cropped != null
                        && "3/4".equals(supportedMeter(readCrop(gray, width, height, cropped))))
                    supported = "3/4";
            }
            if (supported.isBlank()) {
                if (page.boundarySeeds().contains(crop)) {
                    var serif = serifBoundaryMeter(
                            crop, labels, gray, width, height, measure, measures, notes);
                    if (serif != null)
                        serifChoices.computeIfAbsent(measure, ignored -> new HashSet<>()).add(serif);
                }
                continue;
            }
            String[] digits = supported.split("/");
            var reading = new ScoreMeterChange(measure, Integer.parseInt(digits[0]), Integer.parseInt(digits[1]));
            choices.computeIfAbsent(measure, ignored -> new HashSet<>()).add(reading);
        }
        // The fallback only supplies an unclaimed owner. Standard music-font readings take precedence.
        serifChoices.forEach(choices::putIfAbsent);
        return choices.values().stream().filter(readings -> readings.size() == 1)
                .flatMap(Set::stream).toList();
    }

    private static final class Serif {
        private static final MeterFontMatcher MATCHER = new MeterFontMatcher(ClefOctaveNumerals::serifMeterGlyph);
    }

    private static ScoreMeterChange serifBoundaryMeter(
            MeterChangeDetector.Crop crop, byte[] labels, byte[] gray, int width, int height,
            int measure, List<MeasureRegion> measures, List<ScoreNoteEvent> notes) {
        float gap = crop.gap();
        float ownerEdge = measures.get(measure).left() * width;
        if (Math.abs(crop.left() - ownerEdge) > gap * 1.35f || crop.right() - crop.left() > gap * 2.3f)
            return null;
        int pad = Math.round(gap * .2f);
        int bottom = Math.min(crop.bottom(), Math.round(crop.firstLine() + 4.25f * gap));
        var bounded = new MeterChangeDetector.Crop(
                Math.max(0, crop.left() - pad), crop.top(), Math.min(width, crop.right() + pad),
                bottom, crop.firstLine(), gap);
        if (MeterChangeDetector.followingMeasure(bounded, width, height, measures) != measure
                || !MeterChangeDetector.precedesNotes(bounded, labels, gray, width, height, measure, measures, notes))
            return null;
        var reading = readCrop(Serif.MATCHER, gray, width, bounded, bounded.firstLine(), gap);
        String text = supportedMeter(reading);
        if (text.isBlank() && Math.min(reading.topScore(), reading.bottomScore()) >= .84f) {
            var refined = MeterCropStaff.refine(gray, width, height, bounded);
            if (!refined.equals(bounded)
                    && MeterChangeDetector.followingMeasure(refined, width, height, measures) == measure
                    && MeterChangeDetector.precedesNotes(
                            refined, labels, gray, width, height, measure, measures, notes)) {
                reading = readCrop(Serif.MATCHER, gray, width, refined, refined.firstLine(), refined.gap());
                text = supportedMeter(reading);
            }
        }
        if (text.isBlank()) return null;
        String[] digits = text.split("/");
        var meter = new ScoreMeterChange(measure, Integer.parseInt(digits[0]), Integer.parseInt(digits[1]));
        // A strong optical pair can precede a rest or layered voices. A one-beat
        // or shorter header still needs matching duration evidence.
        boolean clearDigits = meter.quarterBeats() > 1
                && Math.min(reading.topScore(), reading.bottomScore()) >= .88f;
        return clearDigits || MeterChangeDetector.supportsWrittenBar(meter, notes) ? meter : null;
    }

    private static String supportedMeter(Reading font) {
        return MusicalOcrEvidence.fontMeter(
                font.text(), font.topScore(), font.bottomScore(), Set.of(), Set.of());
    }

    static Reading readCrop(byte[] gray, int width, int height, MeterChangeDetector.Crop crop) {
        var matcher = Bundled.MATCHER;
        if (matcher == null) return new Reading("", 0, 0);
        var font = readCrop(matcher, gray, width, crop, crop.firstLine(), crop.gap());
        if (!font.text().isBlank()) return font;
        var refined = MeterCropStaff.refine(gray, width, height, crop);
        return refined.equals(crop) ? font
                : readCrop(matcher, gray, width, refined, refined.firstLine(), refined.gap());
    }

    Reading readOwnedCrop(byte[] gray, int width, MeterChangeDetector.Crop crop) {
        return readCrop(this, gray, width, crop, crop.firstLine(), crop.gap());
    }

    private static Reading readCrop(MeterFontMatcher matcher, byte[] gray, int width,
            MeterChangeDetector.Crop crop, int firstLine, float gap) {
        int cw = crop.right() - crop.left(), ch = crop.bottom() - crop.top();
        int[] pixels = MeterCropRaster.copyGray(gray, width, crop.left(), crop.top(), cw, ch);
        MeterCropRaster.prepare(pixels, cw, ch, crop.top(), firstLine, gap, false);
        return matcher.read(pixels, cw, ch,
                Math.round(firstLine + 2 * gap) - crop.top(),
                firstLine - crop.top(), gap);
    }
}
