// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import org.junit.Test;

/** Uses independently rendered Bravura digits and hand-drawn rules. */
public class InlineTripleMeterProofTest {
    private static final int WIDTH = 320, HEIGHT = 180, TOP = 54, GAP = 16;
    private static final int BARLINE = 108;
    private static final MeterChangeDetector.Crop CROP =
            new MeterChangeDetector.Crop(123, TOP - 3, 157, TOP + 4 * GAP + 4, TOP, GAP);

    @Test
    public void completeIndependentStackAndDoubleRuleHaveAuthority() throws Exception {
        assertTrue(proves(signature('3', '4', true)));
    }

    @Test
    public void aSingleRuleDoesNotProveInlineSignatureOwnership() throws Exception {
        assertFalse(proves(signature('3', '4', false)));
    }

    @Test
    public void anotherNumeratorCannotOwnThisThreeAlias() throws Exception {
        assertFalse(proves(signature('2', '4', true)));
    }

    private static boolean proves(byte[] gray) throws Exception {
        Method method =
                InlineTripleMeterNoteAlias.class.getDeclaredMethod(
                        "proved",
                        MeterChangeDetector.Crop.class,
                        byte[].class,
                        int.class,
                        int.class,
                        PlayingTechniqueDetector.Staff.class,
                        float.class);
        method.setAccessible(true);
        var staff = new PlayingTechniqueDetector.Staff(TOP, TOP + 4 * GAP, GAP, 0, 1);
        return (boolean) method.invoke(null, CROP, gray, WIDTH, HEIGHT, staff, BARLINE + 3f);
    }

    private static byte[] signature(char numerator, char denominator, boolean doubleRule)
            throws Exception {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setColor(Color.BLACK);
        for (int row = TOP; row <= TOP + 4 * GAP; row += GAP)
            graphics.fillRect(10, row, WIDTH - 20, 1);
        graphics.fillRect(BARLINE, TOP, 3, 4 * GAP + 1);
        if (doubleRule) graphics.fillRect(BARLINE + 6, TOP, 3, 4 * GAP + 1);
        graphics.setFont(
                Font.createFont(Font.TRUETYPE_FONT, new File("java/assets/Bravura.otf"))
                        .deriveFont(62f));
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        digit(graphics, numerator, TOP, TOP + 2 * GAP);
        digit(graphics, denominator, TOP + 2 * GAP, TOP + 4 * GAP);
        graphics.dispose();
        var data = new byte[WIDTH * HEIGHT];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte) image.getRaster().getSample(i % WIDTH, i / WIDTH, 0);
        return data;
    }

    private static void digit(Graphics2D graphics, char numeral, int top, int bottom) {
        String glyph = Character.toString((char) (0xe080 + numeral - '0'));
        var rect =
                graphics.getFont()
                        .createGlyphVector(graphics.getFontRenderContext(), glyph)
                        .getPixelBounds(null, 0, 0);
        int left = CROP.left() + (CROP.right() - CROP.left() - rect.width) / 2;
        int y = top + (bottom - top - rect.height) / 2;
        graphics.drawString(glyph, left - rect.x, y - rect.y);
    }
}
