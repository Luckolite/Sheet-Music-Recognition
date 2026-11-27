// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TabDetachedRhythmTest {
    private static final int W = 480, H = 390;

    @Test public void separatedPartialBeamAndFullBeamGiveTwoLevels() {
        var tab = tab(List.of());
        var image = paper();
        ink(image, 120, 250, 139, 254);
        assertEquals(0, TabDetachedRhythm.beamLevels(tab, image, W, H, 120));
        ink(image, 120, 263, 190, 267);
        assertEquals(2, TabDetachedRhythm.beamLevels(tab, image, W, H, 120));
    }

    @Test public void tripletNeedsBothHooksAndThePrintedNumber() {
        var tab = tab(List.of(fret(100, 1), fret(220, 1), fret(340, 1), fret(402, 2)));
        var image = paper();
        ink(image, 100, 291, 191, 293);
        ink(image, 249, 291, 340, 293);
        ink(image, 100, 278, 102, 293);
        ink(image, 338, 278, 340, 293);
        assertSame(tab, TabDetachedRhythm.triplets(tab, image, W, H));
        ink(image, 211, 280, 227, 283);
        ink(image, 222, 284, 227, 302);
        ink(image, 216, 291, 226, 294);
        ink(image, 211, 301, 227, 305);
        var recovered = TabDetachedRhythm.triplets(tab, image, W, H);
        assertEquals(List.of(3,3,3,1), recovered.frets().stream().map(TablatureDecoder.Fret::tuplet).toList());
        for (int y = 278; y < 291; y++)
            for (int x = 337; x < 343; x++) image[y*W+x] = (byte)255;
        assertSame(tab, TabDetachedRhythm.triplets(tab, image, W, H));
    }

    private static TablatureDecoder.Staff tab(List<TablatureDecoder.Fret> frets) {
        return new TablatureDecoder.Staff(20,32,-1,frets,List.of(60f,440f));
    }

    private static TablatureDecoder.Fret fret(int x, int duration) {
        return new TablatureDecoder.Fret(x,148,4,7,duration,0,0,0);
    }

    private static byte[] paper() {
        var image = new byte[W*H];
        Arrays.fill(image, (byte)255);
        return image;
    }

    private static void ink(byte[] image, int left,int top,int right,int bottom) {
        for (int y = top; y <= bottom; y++) Arrays.fill(image,y*W+left,y*W+right+1,(byte)0);
    }
}
