// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RepeatNotCutTimeTest {
    private static final int W = 320, H = 210, TOP = 79, GAP = 13;

    @Test
    public void repeatDotsAndBarlinesDoNotCreateCutTime() {
        byte[] gray = new byte[W * H], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= TOP + 4 * GAP; y += GAP) box(gray,20,y,275,1);
        box(gray,38,57,16,99);
        for (int y = 57; y < 156; y++) Arrays.fill(labels,y * W + 38,y * W + 54,(byte) 3);
        box(gray,130,TOP,5,4 * GAP + 1);
        box(gray,137,TOP,2,4 * GAP + 1);
        for (int cy : new int[] {TOP + 19,TOP + 32})
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++)
                    if (dx * dx + dy * dy <= 4) gray[(cy + dy) * W + 142 + dx] = 0;
        assertFalse(PrintedRepeatGlyphs.provedColumns(gray,W,H,TOP,TOP + 4 * GAP,GAP).isEmpty());
        assertTrue(CommonTimeMeter.candidates(labels,gray,W,H,TOP,GAP,0).isEmpty());
    }

    private static void box(byte[] gray,int x,int y,int w,int h) {
        for (int yy = y; yy < y + h; yy++) Arrays.fill(gray,yy * W + x,yy * W + x + w,(byte) 0);
    }
}
