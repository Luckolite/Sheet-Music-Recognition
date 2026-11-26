// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PlainEndingSelectorTest {
    private static final int W = 550, H = 270, GAP = 16, ROOF = 88;
    private static final int FIRST = 180, CLOSE = 304, SECOND = 311;
    private static final List<MeasureRegion> BARS = List.of(
            new MeasureRegion(40f / W, 172f / W, .48f, .83f),
            new MeasureRegion(188f / W, 298f / W, .48f, .83f),
            new MeasureRegion(317f / W, 502f / W, .48f, .83f));
    private static final List<PlayingTechniqueDetector.Staff> STAFF = List.of(
            new PlayingTechniqueDetector.Staff(143, 207, GAP, 0, 1));

    @Test
    public void plainFirstAndSecondOwnTheirAdjacentBrackets() {
        var endings = detect(true, 0, true);
        assertEquals(2, endings.size());
        assertEquals(List.of(1), endings.get(0).passes());
        assertEquals(List.of(2), endings.get(1).passes());
        assertEquals(1, endings.get(0).start());
        assertEquals(2, endings.get(0).end());
        assertEquals(2, endings.get(1).start());
        assertEquals(3, endings.get(1).end());
    }

    @Test
    public void requiresAdjacentRoofsAndTheFirstClosingHook() {
        assertTrue(detect(false, 0, true).isEmpty());
        assertTrue(detect(true, 13, true).isEmpty());
        assertTrue(detect(true, 0, false).isEmpty());
    }

    private static List<PrintedEndingBrackets.Mark> detect(boolean second, int offset, boolean closed) {
        var drawing = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        var g = drawing.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.fillRect(FIRST, ROOF, CLOSE - FIRST + 2, 2);
        g.fillRect(FIRST, ROOF, 2, 30);
        if (closed) g.fillRect(CLOSE, ROOF, 2, 30);
        int x = FIRST + 12, y = ROOF + 12;
        g.fillRect(x + 2, y, 3, 21);
        g.fillRect(x, y + 2, 3, 2);
        g.fillRect(x, y + 20, 6, 2);
        if (second) {
            g.fillRect(SECOND, ROOF + offset, 195, 2);
            g.fillRect(SECOND, ROOF + offset, 2, 30);
            x = SECOND + 9;
            y = ROOF + offset + 12;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(2.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            var path = new Path2D.Float();
            path.moveTo(x, y + 4);
            path.curveTo(x, y - 2, x + 13, y - 2, x + 12, y + 5);
            path.curveTo(x + 12, y + 11, x + 3, y + 13, x, y + 20);
            path.lineTo(x + 12, y + 20);
            g.draw(path);
        }
        g.dispose();
        var gray = ((DataBufferByte) drawing.getRaster().getDataBuffer()).getData();
        return PrintedEndingBrackets.detect(gray, W, H, STAFF, BARS, List.of());
    }
}
