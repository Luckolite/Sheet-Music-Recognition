// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class FadedRepeatOuterBarTest {
    private static final int W = 500, H = 240, GAP = 16, TOP = 80, BOTTOM = 144;
    private static final List<PlayingTechniqueDetector.Staff> STAFF = List.of(
            new PlayingTechniqueDetector.Staff(TOP, BOTTOM, GAP, 0, 1));

    @Test
    public void fadedStripePreservesTheIncomingRepeatAndItsGeometry() {
        var gray = page(182);
        var bars = PrintedRepeatGlyphs.detect(gray, W, H, STAFF, incomingBars());
        assertEquals(1, bars.size());
        assertFalse(bars.get(0).outgoing());
        assertEquals(1, bars.get(0).boundary());
        var columns = PrintedRepeatGlyphs.provedColumns(gray, W, H, TOP, BOTTOM, GAP);
        assertTrue(columns.get(124));
        assertTrue(columns.get(125));
        assertTrue(columns.get(138));
        assertFalse(columns.get(132));
    }

    @Test
    public void recoveredThickBarAlsoSupportsOutgoingRepeat() {
        byte[] original = page(182), mirrored = original.clone();
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                mirrored[y * W + x] = original[y * W + W - 1 - x];
        var bars = PrintedRepeatGlyphs.detect(mirrored, W, H, STAFF,
                List.of(region(28, W - 138), region(W - 116, 475)));
        assertEquals(1, bars.size());
        assertTrue(bars.get(0).outgoing());
        assertEquals(1, bars.get(0).boundary());
    }

    @Test
    public void whiteSpaceBetweenThreeIndependentRulesKeepsThemSeparate() {
        var gray = page(255);
        assertTrue(PrintedRepeatGlyphs.detect(gray, W, H, STAFF, incomingBars()).isEmpty());
        assertTrue(PrintedRepeatGlyphs.provedColumns(gray, W, H, TOP, BOTTOM, GAP).isEmpty());
    }

    private static List<MeasureRegion> incomingBars() {
        return List.of(region(40, 113), region(141, 470));
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion(left / (float) W, right / (float) W, .24f, .72f);
    }

    private static byte[] page(int seamTone) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += GAP) rect(gray, 45, y, 425, 1, 0);
        rect(gray, 120, TOP, 4, BOTTOM - TOP + 1, 0);
        rect(gray, 124, TOP, 2, BOTTOM - TOP + 1, seamTone);
        rect(gray, 126, TOP, 4, BOTTOM - TOP + 1, 0);
        rect(gray, 137, TOP, 3, BOTTOM - TOP + 1, 0);
        rect(gray, 151, 102, 5, 5, 0);
        rect(gray, 151, 118, 5, 5, 0);
        return gray;
    }

    private static void rect(byte[] gray, int x, int y, int width, int height, int tone) {
        for (int yy = y; yy < y + height; yy++)
            Arrays.fill(gray, yy * W + x, yy * W + x + width, (byte) tone);
    }
}
