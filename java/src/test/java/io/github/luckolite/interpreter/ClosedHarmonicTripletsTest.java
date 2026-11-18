// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ClosedHarmonicTripletsTest {
    private static final int W = 136, H = 116;
    private static final float GAP = 16;

    @Test
    public void anotherDurationDotPreventsBorrowedDotRecovery() throws Exception {
        var image = paper();
        dot(image, 75, 65);
        assertTrue(borrowed(image));
        dot(image, 60, 56);
        assertFalse(borrowed(image));

        var independent = paper();
        dot(independent, 60, 56);
        assertFalse(borrowed(independent));
    }

    @Test
    public void anIndependentSecondRailVetoesSingleBeamCorrection() throws Exception {
        var image = paper();
        var first = shaft(42, 54);
        var last = shaft(77, 48);
        paintRail(image, 42, 77, 54, 48, 4);
        assertFalse(secondary(image, first, last));
        paintRail(image, 42, 77, 54, 48, 16);
        assertTrue(secondary(image, first, last));
    }

    private static boolean borrowed(byte[] image) throws Exception {
        var rule = ClosedHarmonicTriplets.class.getDeclaredMethod("dotBelongsToNext",
                byte[].class, int.class, int.class, float.class, float.class, float.class,
                float.class, float.class);
        rule.setAccessible(true);
        return (boolean) rule.invoke(null, image, W, H, 43f, 59f, 75f, 43f, GAP);
    }

    private static boolean secondary(byte[] image, PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b) throws Exception {
        var rule = ClosedHarmonicTriplets.class.getDeclaredMethod("containsAdditionalRail",
                byte[].class, int.class, int.class, PrintedBeamShafts.Shaft.class,
                PrintedBeamShafts.Shaft.class, float.class);
        rule.setAccessible(true);
        return (boolean) rule.invoke(null, image, W, H, a, b, GAP);
    }

    private static PrintedBeamShafts.Shaft shaft(int x, int endY) {
        return new PrintedBeamShafts.Shaft(x-1, x+1, endY-32, endY, 1, List.of());
    }

    private static void paintRail(byte[] im, int from, int to, int y0, int y1, int inset) {
        for (int x = from; x <= to; x++) {
            int y = Math.round(y0+(y1-y0)*(x-from)/(float)(to-from))-inset;
            for (int d=-2; d<=2; d++) im[(y+d)*W+x]=0;
        }
    }

    private static void dot(byte[] image, int x, int y) {
        for (int dy=-3; dy<=3; dy++)
            for (int dx=-3; dx<=3; dx++)
                if (dx*dx+dy*dy<=12) image[(y+dy)*W+x+dx]=0;
    }

    private static byte[] paper() {
        var image=new byte[W*H];
        Arrays.fill(image,(byte)255);
        return image;
    }
}
