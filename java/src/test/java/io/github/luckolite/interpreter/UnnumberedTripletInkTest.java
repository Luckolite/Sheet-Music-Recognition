// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class UnnumberedTripletInkTest {
    private static final int WIDTH = 256, HEIGHT = 304;
    private static final float GAP = 16;
    private static final float[] X = {92, 120, 148}, Y = {134, 134, 134};
    private static final List<PrintedBeamShafts.Shaft> SHAFTS = List.of(
            shaft(92), shaft(120), shaft(148));

    @Test public void clearAndNumberedBeamsAreDistinguished() {
        byte[] paper = clearBeam();
        assertTrue(clear(paper));
        paint(paper, 116, 183, ".#####.", "##...##", ".....##", "....##.",
                "...###.", "....##.", ".....##", ".....##", "##...##", ".#####.");
        assertFalse(clear(paper));
    }

    @Test public void bracketsOnEitherSidePreventAutomaticSuppression() {
        for (int y : new int[] {99, 208}) {
            byte[] paper = clearBeam();
            assertTrue(clear(paper));
            for (int x = 91; x <= 110; x++) paper[y * WIDTH + x] = 0;
            for (int x = 131; x <= 151; x++) paper[y * WIDTH + x] = 0;
            for (int dy = 1; dy <= 5; dy++) {
                int inward = y < 134 ? y + dy : y - dy;
                paper[inward * WIDTH + 91] = paper[inward * WIDTH + 151] = 0;
            }
            assertFalse(clear(paper));
        }
    }

    @Test public void uncertainInkAndMarksJoinedToRailAreRetained() {
        byte[] uncertain = clearBeam();
        for (int dy = 0; dy < 12; dy++) uncertain[(183 + dy) * WIDTH + 118 + dy / 4] = (byte) 190;
        assertFalse(clear(uncertain));
        byte[] joined = clearBeam();
        for (int dy = 0; dy <= 18; dy++) joined[(169 + dy) * WIDTH + 116] = 0;
        assertFalse(clear(joined));
    }

    private static void paint(byte[] paper, int left, int top, String... lines) {
        for (int dy = 0; dy < lines.length; dy++)
            for (int dx = 0; dx < lines[dy].length(); dx++)
                if (lines[dy].charAt(dx) == '#') paper[(top + dy) * WIDTH + left + dx] = 0;
    }

    private static boolean clear(byte[] paper) {
        return UnnumberedTripletInk.clear(paper, WIDTH, HEIGHT, GAP, X, Y, SHAFTS);
    }

    private static PrintedBeamShafts.Shaft shaft(int x) {
        return new PrintedBeamShafts.Shaft(x-1, x+1, 134, 170, 1, List.of());
    }

    private static byte[] clearBeam() {
        byte[] paper = new byte[WIDTH * HEIGHT];
        Arrays.fill(paper, (byte) 255);
        for (int y = 169; y <= 171; y++) Arrays.fill(paper, y*WIDTH+91, y*WIDTH+150, (byte) 0);
        for (int y = 134; y <= 170; y++)
            for (var shaft : SHAFTS)
                Arrays.fill(paper, y*WIDTH+shaft.left(), y*WIDTH+shaft.right()+1, (byte) 0);
        return paper;
    }
}
