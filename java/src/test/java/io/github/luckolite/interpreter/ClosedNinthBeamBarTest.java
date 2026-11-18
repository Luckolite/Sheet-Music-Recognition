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

public class ClosedNinthBeamBarTest {
    private static final int WIDTH = 1280, HEIGHT = 500, GAP = 16;
    private static final int TOP = 245, BOTTOM = TOP + 4 * GAP;
    private static final int HIGH = BOTTOM - 24, LOW = HIGH + 72;
    private static final int LEFT = 136, SPAN = 780, RIGHT = LEFT + SPAN;
    private static final int FIRST = LEFT + 71, ADVANCE = 52;
    private static final int UP_TIP = TOP - 23, DOWN_TIP = LOW + 56;

    @Test
    public void restoresSharedFirstEighthAndCompleteLiteralBeamGroups() throws Exception {
        var prior = page();
        var image = paper();
        var actual = apply(prior, image, image);
        assertEquals(
                List.of(1, 1, 2, 2),
                actual.notes().subList(0, 4).stream().map(ScoreNoteEvent::beamCount).toList());
        assertEquals(
                List.of(1, 1, 1, 1),
                actual.notes().subList(0, 4).stream().map(ScoreNoteEvent::stemDirection).toList());
        assertEquals(0, actual.notes().get(1).unbeamedDurationBeats(), 0);
        assertEquals(
                List.of(List.of(1, 3), List.of(3, 3, 3), List.of(1, 3), List.of(3, 3, 3)),
                actual.beamSource().groups().stream().map(ScoreBeamSource.Group::bridges).toList());
        assertEquals(prior.measures(), actual.measures());
    }

    @Test
    public void onePlaneMustAlsoShowTheSignatureAndUnbrokenPrimaryBeam() throws Exception {
        var prior = page();
        var image = paper();
        var missing = image.clone();
        paint(missing, LEFT - 14, LEFT + 30, TOP - 3, BOTTOM + 3, 255);
        assertSame(prior, apply(prior, image, missing));
        var broken = image.clone();
        paint(broken, FIRST + 24, FIRST + 40, UP_TIP - 4, UP_TIP + 12, 255);
        assertSame(prior, apply(prior, image, broken));
    }

    private static ScorePageInterpretation apply(ScorePageInterpretation page, byte[] a, byte[] b) {
        return ClosedNinthBeamBar.restore(page, a, b, WIDTH, HEIGHT, 0);
    }

    private static ScorePageInterpretation page() {
        var previous = new MeasureRegion(.10f, .70f, .05f, .20f);
        var bar =
                new MeasureRegion(
                        LEFT / (float) WIDTH,
                        RIGHT / (float) WIDTH,
                        (TOP - 35f) / HEIGHT,
                        (BOTTOM + 32f) / HEIGHT);
        var next = new MeasureRegion((RIGHT + 12f) / WIDTH, .97f, bar.top(), bar.bottom());
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 14; slot++) {
            notes.add(head(slot, HIGH, 3, true));
            notes.add(head(slot, LOW, -6, false));
        }
        return new ScorePageInterpretation(
                List.of(previous, bar, next),
                notes,
                0,
                List.of(),
                List.of(),
                List.of(new ScoreMeterChange(1, 4, 4)),
                List.of());
    }

    private static ScoreNoteEvent head(int slot, int y, int step, boolean upper) {
        int beams = slot == 0 && !upper ? 0 : slot == 7 ? 1 : 2;
        int duration = slot == 0 && !upper ? 1 : 0;
        return new ScoreNoteEvent(
                        1,
                        (FIRST + slot * ADVANCE - LEFT) / (float) SPAN,
                        step,
                        0,
                        1,
                        y / (float) HEIGHT,
                        false,
                        0,
                        beams,
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                        duration,
                        1,
                        0,
                        0,
                        ScoreNoteEvent.CLEF_TREBLE)
                .withStemDirection(slot == 0 && upper ? 0 : slot < 3 ? 1 : -1);
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
                    four, LEFT + 7 - bounds.width / 2 - bounds.x, y - bounds.height / 2 - bounds.y);
        graphics.dispose();
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int y = TOP; y <= BOTTOM; y += GAP) paint(pixels, 40, WIDTH - 20, y, y, 0);
        paint(pixels, RIGHT + 3, RIGHT + 6, TOP, BOTTOM, 0);
        for (int slot = 0; slot < 14; slot++) {
            int x = FIRST + slot * ADVANCE;
            boolean up = slot < 3;
            int stem = x + (up ? 10 : -10);
            int tip = up ? UP_TIP : DOWN_TIP;
            paint(pixels, stem - 1, stem + 1, up ? tip : HIGH, up ? LOW : tip, 0);
            for (int center : new int[] {HIGH, LOW}) {
                for (int dx = -10; dx <= 10; dx++) {
                    for (int dy = -5; dy <= 5; dy++) {
                        if (dx * dx / 100f + dy * dy / 25f <= 1)
                            pixels[(center + dy) * WIDTH + x + dx] = 0;
                    }
                }
            }
            if (slot == 0 || slot == 3 || slot == 7 || slot == 10) continue;
            paint(pixels, stem - ADVANCE, stem, up ? tip : tip - 6, up ? tip + 6 : tip, 0);
            if (slot == 1 || slot == 8) continue;
            int secondary = tip + (up ? 14 : -14);
            paint(
                    pixels,
                    stem - ADVANCE,
                    stem,
                    up ? secondary : secondary - 6,
                    up ? secondary + 6 : secondary,
                    0);
        }
        return pixels;
    }

    private static void paint(byte[] pixels, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) pixels[y * WIDTH + x] = (byte) value;
    }
}
