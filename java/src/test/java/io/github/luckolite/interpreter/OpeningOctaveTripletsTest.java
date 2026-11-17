// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class OpeningOctaveTripletsTest {
    private static final int WIDTH = 1910, HEIGHT = 450, GAP = 16;
    private static final int LEFT = 120, SPAN = 544, STRIDE = SPAN + 18;
    private static final int TOP = 188, BOTTOM = TOP + 4 * GAP;
    private static final int HIGH = BOTTOM - 20, LOW = HIGH + 56;
    private static final int FIRST = LEFT + 28, ADVANCE = 44;

    @Test
    public void threePrintedBarsRestoreTheirTwelveUnnumberedPrimaryBeams() throws Exception {
        var prior = page();
        var image = paper();
        var actual = apply(prior, image, image);
        assertEquals(
                72,
                actual.notes().stream()
                        .filter(
                                n ->
                                        n.tupletDivisor() == 3
                                                && n.tupletNormalNotes() == 2
                                                && n.beamCount() == 1)
                        .count());
        assertEquals(12, actual.beamSource().groups().size());
        for (var group : actual.beamSource().groups()) {
            assertEquals(List.of(1, 1), group.bridges());
            assertEquals(ScoreBeamSource.TupletMark.UNNUMBERED, group.tupletMark());
        }
        assertEquals(prior.measures(), actual.measures());
    }

    @Test
    public void numeralAndBrokenRailAreIndependent() throws Exception {
        var prior = page();
        var image = paper();
        var missing = image.clone();
        paint(missing, FIRST + 18, FIRST + 24, TOP - 30, TOP - 19, 255);
        assertSame(prior, apply(prior, image, missing));

        var numbered = image.clone();
        int[][] shape = {
            {2, 8}, {1, 9}, {1, 10}, {8, 11}, {9, 11}, {9, 11}, {8, 11}, {7, 10}, {4, 8}, {4, 8},
            {6, 9}, {8, 10}, {9, 11}, {9, 11}, {9, 11}, {8, 11}, {7, 10}, {1, 9}, {1, 8}, {2, 7}
        };
        for (int y = 0; y < shape.length; y++) {
            int left = FIRST + 34, row = TOP - 63 + y;
            paint(numbered, left + shape[y][0], left + shape[y][1], row, row, 0);
        }
        var actual = apply(prior, image, numbered);
        assertEquals(72, actual.notes().stream().filter(n -> n.tupletDivisor() == 3).count());
        assertEquals(
                11,
                actual.beamSource().groups().stream()
                        .filter(g -> g.tupletMark() == ScoreBeamSource.TupletMark.UNNUMBERED)
                        .count());
    }

    private static ScorePageInterpretation apply(ScorePageInterpretation page, byte[] a, byte[] b) {
        return OpeningOctaveTriplets.close(page, new byte[a.length], a, b, WIDTH, HEIGHT, 0);
    }

    private static ScorePageInterpretation page() {
        var bars = new ArrayList<MeasureRegion>();
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int bar = 0; bar < 3; bar++) {
            int left = LEFT + bar * STRIDE;
            bars.add(region(left, left + SPAN));
            for (int slot = 0; slot < 12; slot++) {
                notes.add(head(bar, slot, HIGH, 3));
                notes.add(head(bar, slot, LOW, -4));
            }
        }
        bars.add(region(LEFT, LEFT + SPAN));
        return new ScorePageInterpretation(
                bars,
                notes,
                0,
                List.of(),
                List.of(),
                List.of(new ScoreMeterChange(0, 4, 4)),
                List.of());
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion(
                left / (float) WIDTH,
                right / (float) WIDTH,
                (TOP - 35f) / HEIGHT,
                (BOTTOM + 32f) / HEIGHT);
    }

    private static ScoreNoteEvent head(int bar, int slot, int y, int step) {
        int beams = slot == 5 && step > 0 ? 2 : 1;
        return new ScoreNoteEvent(
                        bar,
                        (28 + slot * ADVANCE) / (float) SPAN,
                        step,
                        0,
                        1,
                        y / (float) HEIGHT,
                        false,
                        0,
                        beams,
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                        0,
                        1,
                        0,
                        0,
                        ScoreNoteEvent.CLEF_TREBLE)
                .withStemDirection(slot < 3 ? 1 : -1);
    }

    private static byte[] paper() throws Exception {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setColor(Color.BLACK);
        var font =
                Font.createFont(Font.TRUETYPE_FONT, new File("java/assets/Bravura.otf"))
                        .deriveFont(64f);
        graphics.setFont(font);
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        String four = "\ue084";
        var bounds =
                font.createGlyphVector(graphics.getFontRenderContext(), four)
                        .getPixelBounds(null, 0, 0);
        for (int y : new int[] {TOP + GAP, TOP + 3 * GAP})
            graphics.drawString(
                    four, LEFT - bounds.width / 2 - bounds.x, y - bounds.height / 2 - bounds.y);
        graphics.dispose();
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int y = TOP; y <= BOTTOM; y += GAP) paint(pixels, 40, WIDTH - 20, y, y, 0);
        for (int bar = 0; bar < 3; bar++) {
            int line = LEFT + bar * STRIDE + SPAN + 9;
            paint(pixels, line - 1, line + 2, TOP, BOTTOM, 0);
            for (int slot = 0; slot < 12; slot++) {
                int x = FIRST + bar * STRIDE + slot * ADVANCE;
                boolean up = slot < 3;
                int stem = x + (up ? 10 : -10);
                int tip = up ? TOP - 25 : LOW + 51;
                paint(pixels, stem - 1, stem + 1, up ? tip : HIGH, up ? LOW : tip, 0);
                for (int center : new int[] {HIGH, LOW}) {
                    for (int dx = -10; dx <= 10; dx++) {
                        for (int dy = -5; dy <= 5; dy++) {
                            if (dx * dx / 100f + dy * dy / 25f <= 1)
                                pixels[(center + dy) * WIDTH + x + dx] = 0;
                        }
                    }
                }
                if (slot % 3 != 0)
                    paint(pixels, stem - ADVANCE, stem, up ? tip : tip - 6, up ? tip + 6 : tip, 0);
            }
        }
        return pixels;
    }

    private static void paint(byte[] pixels, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) pixels[y * WIDTH + x] = (byte) value;
    }
}
