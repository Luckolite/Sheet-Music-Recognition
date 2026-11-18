// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class InterruptedStaffHeaderTest {
    private static final int WIDTH = 640, HEIGHT = 230, FIRST = 78, GAP = 16;
    private static final byte CLEF = OmrMeasurePostProcessor.CLEF_OR_KEY;
    private static final List<MeasureRegion> REGIONS = List.of(
            region(60,140), region(158,446), region(464,565));

    @Test public void interruptedStaffWithNewMeterDoesNotAddAnEmptyBar() throws Exception {
        byte[] gray = new byte[WIDTH * HEIGHT], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int y = FIRST; y <= FIRST + 4 * GAP; y += GAP) {
            paint(gray, 16, 160, y, y, 0);
            paint(gray, 258, 615, y, y, 0);
        }
        paint(gray, 146,149,FIRST,FIRST+4*GAP,0);
        paint(gray, 155,158,FIRST,FIRST+4*GAP,0);
        var font = Font.createFont(Font.TRUETYPE_FONT, Path.of("java/assets/Bravura.otf").toFile());
        stamp(font, gray, labels, 0xe050, 265, 52, 110, CLEF);
        for (int i = 0; i < 4; i++)
            stamp(font, gray, labels, 0xe260, 297+i*13, FIRST+i*4, 47, CLEF);
        stamp(font, gray, labels, 0xe082, 441, FIRST+2, 29, (byte) 5);
        stamp(font, gray, labels, 0xe084, 441, FIRST+2*GAP+2, 29, (byte) 5);

        assertEquals(List.of(REGIONS.get(0), REGIONS.get(2)),
                InterruptedStaffHeader.discard(REGIONS, labels, gray, gray.clone(), WIDTH, HEIGHT));
        var bridged = gray.clone();
        paint(bridged, 162, 257, FIRST, FIRST, 0);
        assertEquals(REGIONS, InterruptedStaffHeader.discard(
                REGIONS, labels, gray, bridged, WIDTH, HEIGHT));
        var noMeter = gray.clone();
        paint(noMeter, 438, 481, FIRST, FIRST+4*GAP, 255);
        assertEquals(REGIONS, InterruptedStaffHeader.discard(
                REGIONS, labels, noMeter, gray, WIDTH, HEIGHT));
        var playable = labels.clone();
        paint(playable, 406, 422, FIRST+30, FIRST+37, OmrMeasurePostProcessor.NOTEHEAD);
        assertEquals(REGIONS, InterruptedStaffHeader.discard(
                REGIONS, playable, gray, gray, WIDTH, HEIGHT));
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion((float) left / WIDTH, (float) right / WIDTH,
                45f / HEIGHT, 176f / HEIGHT);
    }

    private static void stamp(Font font, byte[] gray, byte[] labels, int code, int x,
            int y, int height, byte label) {
        var glyph = font.deriveFont(64f).createGlyphVector(new FontRenderContext(null,true,true),
                String.valueOf((char) code)).getOutline();
        var bounds = glyph.getBounds2D();
        double scale = height / bounds.getHeight();
        var transform = new AffineTransform();
        transform.translate(x,y);
        transform.scale(scale,scale);
        transform.translate(-bounds.getX(),-bounds.getY());
        var ink = transform.createTransformedShape(glyph);
        var area = ink.getBounds();
        for (int row=area.y; row<area.y+area.height; row++)
            for (int col=area.x; col<area.x+area.width; col++)
                if (ink.contains(col+.5,row+.5)) {
                    gray[row*WIDTH+col]=0;
                    labels[row*WIDTH+col]=label;
                }
    }

    private static void paint(byte[] plane, int x0, int x1, int y0, int y1, int ink) {
        for (int y=y0; y<=y1; y++)
            Arrays.fill(plane,y*WIDTH+x0,y*WIDTH+x1+1,(byte) ink);
    }
}
