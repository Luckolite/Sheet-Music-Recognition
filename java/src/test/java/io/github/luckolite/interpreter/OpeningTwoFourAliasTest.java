// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class OpeningTwoFourAliasTest {
    private static final int WIDTH = 468, HEIGHT = 368;
    private static final int LEFT = 78, RIGHT = 324, X = 113, TOP_RULE = 200, GAP = 16;
    private static final List<MeasureRegion> MEASURES =
            List.of(new MeasureRegion(0, 1, 8f / HEIGHT, 140f / HEIGHT),
                    new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH,
                            168f / HEIGHT, 300f / HEIGHT));
    private static final ScoreNoteEvent ALIAS = note(X, 226, 5, 0);
    private static final ScoreNoteEvent FIRST = note(179, 248, 2, 1);
    private static final List<ScoreNoteEvent> HEADS = List.of(ALIAS,FIRST);

    @Test
    public void stackedTwoOverFourOwnsTheStemlessQuarterAtTheOpening() throws Exception {
        var draw = signature(true, true);
        var result = capture(HEADS, draw, draw);
        assertEquals(List.of(FIRST), result.notes());
        assertEquals(List.of(new ScoreMeterChange(1, 2, 4)), result.inlineMeters());
    }

    @Test
    public void theNumeratorAndDenominatorMustSurviveInEachPlane() throws Exception {
        var draw = signature(true, true);
        for (var damaged : List.of(signature(true, false),signature(false, true))) {
            assertEquals(HEADS, capture(HEADS, draw, damaged).notes());
            assertEquals(HEADS, capture(HEADS, damaged, draw).notes());
        }
    }

    @Test
    public void aStemmedNoteAndAnEarlierAttackBothKeepTheirOwnership() throws Exception {
        var draw = signature(true, true);
        var withStem = List.of(ALIAS.withStemDirection(1),FIRST);
        assertEquals(withStem, capture(withStem, draw, draw).notes());
        var earlier = List.of(note(95, 247, 2, 1), ALIAS,FIRST);
        assertEquals(earlier, capture(earlier, draw, draw).notes());
    }

    private static MeterNumeralWholeNoteAlias.Capture capture(
            List<ScoreNoteEvent> notes, byte[] normalized, byte[] source) {
        var labels = new byte[WIDTH * HEIGHT];
        for (int y = 219; y <= 232; y++)
            for (int x = X - 8; x <= X + 8; x++)
                if ((normalized[y * WIDTH + x] & 255) < 160)
                    labels[y * WIDTH + x] = OmrMeasurePostProcessor.NOTEHEAD;
        return MeterNumeralWholeNoteAlias.capture(notes, MEASURES, labels, normalized, source, WIDTH,HEIGHT);
    }

    private static ScoreNoteEvent note(int x, int y, int step, int stem) {
        return new ScoreNoteEvent(1, (x - LEFT) / (float)(RIGHT - LEFT), step, 0, 1, y / (float)HEIGHT,
                false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                1, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE).withStemDirection(stem);
    }

    private static byte[] signature(boolean numerator,boolean denominator) throws Exception {
        var buffer = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = buffer.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0,0,WIDTH,HEIGHT);
        graphics.setColor(Color.BLACK);
        for (int i=0;i<5;i++) graphics.drawLine(25,TOP_RULE+i*GAP,400,TOP_RULE+i*GAP);
        graphics.setFont(Font.createFont(Font.TRUETYPE_FONT,new File("java/assets/Bravura.otf"))
                .deriveFont(62f));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (numerator) draw(graphics,"\ue082",TOP_RULE,TOP_RULE+2*GAP);
        if (denominator) draw(graphics,"\ue084",TOP_RULE+2*GAP,TOP_RULE+4*GAP+2);
        graphics.dispose();
        var data = new byte[WIDTH*HEIGHT];
        for(int i=0;i<data.length;i++)
            data[i]=(byte)buffer.getRaster().getSample(i%WIDTH,i/WIDTH,0);
        return data;
    }

    private static void draw(Graphics2D graphics, String glyph,int top,int bottom) {
        var box=graphics.getFont().createGlyphVector(graphics.getFontRenderContext(),glyph)
                .getPixelBounds(null,0,0);
        graphics.drawString(glyph,X-box.width/2-box.x,top+(bottom-top-box.height)/2-box.y);
    }
}
