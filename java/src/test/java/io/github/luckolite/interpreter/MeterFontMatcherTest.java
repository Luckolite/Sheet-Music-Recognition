// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import org.junit.Test;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import static org.junit.Assert.*;

/** Original synthetic staff signatures; no commercial score image is used. */
public class MeterFontMatcherTest {
    private static final File FONT = new File("java/assets/Bravura.otf");

    @Test
    public void readsPrintedStackedDigitsAfterStaffRemoval() throws Exception {
        var matcher = new MeterFontMatcher(FONT);
        assertEquals("2/4", matcher.read(signature("2", "4", 32), 32, 73, 36, 3, 16.5f).text());
        assertEquals("12/8", matcher.read(signature("12", "8", 48), 48, 73, 36, 3, 16.5f).text());
    }

    @Test
    public void narrowBarlineCropIsNotAOneOneMeter() throws Exception {
        var matcher = new MeterFontMatcher(FONT);
        int[] pixels = new int[16 * 73];
        java.util.Arrays.fill(pixels, 0xffffffff);
        for (int y = 0; y < 73; y++) pixels[y * 16 + 7] = 0xff000000;
        assertEquals("", matcher.read(pixels, 16, 73, 36, 3, 16.5f).text());
    }

    @Test
    public void serifMeterNeedsACompleteStackAndMatchingWrittenRhythm() throws Exception {
        var shortMeter = new ScoreMeterChange(0, 3, 8);
        assertTrue(serifScene("3", "8", 3, false).contains(shortMeter));
        assertFalse(serifScene("3", "8", 2, false).contains(shortMeter));
        assertFalse(serifScene("3", "8", 3, true).contains(shortMeter));
        assertFalse(serifScene("1", "4", 6, false).contains(new ScoreMeterChange(0, 1, 4)));
    }

    private static java.util.List<ScoreMeterChange> serifScene(
            String numerator, String denominator, int attacks, boolean removeLower) throws Exception {
        int width = 560, height = 250, first = 67, gap = 19, left = 177, body = 40;
        var stamp = new BufferedImage(body, 4 * gap + 1, BufferedImage.TYPE_INT_ARGB);
        var graphics = stamp.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, stamp.getWidth(), stamp.getHeight());
        var font = Font.createFont(Font.TRUETYPE_FONT, new File("training/fonts/NotoSerif.ttf"))
                .deriveFont(Font.BOLD, 39f);
        graphics.setFont(font);
        graphics.setColor(Color.BLACK);
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        print(graphics, numerator, body, 0, 2 * gap);
        if (!removeLower) print(graphics, denominator, body, 2 * gap, 4 * gap + 1);
        graphics.dispose();
        var gray = new byte[width * height];
        var labels = new byte[gray.length];
        java.util.Arrays.fill(gray, (byte) 255);
        for (int k = 0; k < 5; k++) {
            int y = first + k * gap;
            for (int x = 15; x < width - 15; x++) {
                gray[y * width + x] = 0;
                labels[y * width + x] = OmrMeasurePostProcessor.STAFF;
            }
        }
        for (int y = 0; y < stamp.getHeight(); y++) {
            for (int x = 0; x < body; x++) {
                int ink = stamp.getRGB(x, y) & 255;
                if (ink >= 190) continue;
                int at = (first + y) * width + left + x;
                gray[at] = (byte) ink;
                labels[at] = OmrMeasurePostProcessor.SYMBOL;
            }
        }
        var measure = new MeasureRegion(left / (float) width, .94f, .15f, .68f);
        var notes = new java.util.ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < attacks; i++) {
            notes.add(new ScoreNoteEvent(
                    0, .25f + .10f * i, 1, 0, 1, .4f, false, 0, 1, 0, 0f));
        }
        return MeterFontMatcher.standaloneReadings(labels, gray, width, height, java.util.List.of(measure), notes);
    }

    private static int[] signature(String numerator, String denominator, int width)
            throws Exception {
        BufferedImage image = new BufferedImage(width, 73, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, 73);
        Font font = Font.createFont(Font.TRUETYPE_FONT, FONT).deriveFont(62f);
        graphics.setFont(font);
        graphics.setColor(Color.BLACK);
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        print(graphics, glyphs(numerator), width, 3, 36);
        print(graphics, glyphs(denominator), width, 36, 73);
        graphics.dispose();
        return image.getRGB(0, 0, width, 73, null, 0, width);
    }

    private static String glyphs(String digits) {
        return digits.codePoints()
                .map(ch -> 0xe080 + ch - '0')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private static void print(Graphics2D graphics, String digits, int width, int top, int bottom) {
        var bounds =
                graphics.getFont()
                        .createGlyphVector(graphics.getFontRenderContext(), digits)
                        .getPixelBounds(null, 0, 0);
        int left = (width - bounds.width) / 2;
        int vertical = top + (bottom - top - bounds.height) / 2;
        graphics.drawString(digits, left - bounds.x, vertical - bounds.y);
    }
}
