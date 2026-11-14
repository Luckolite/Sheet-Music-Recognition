// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.Test;

public final class ShortDyadBeamEndTest {
    private static final int W = 180;
    private static final float GAP = 16;
    private static final int[] A = {24, 98, 1};
    private static final int[] B = {65, 91, 1};
    private static final int[] C = {106, 84, 1};

    @Test
    public void needsIndependentRailsAndAnUnextendedEndpoint() {
        byte[] sheet = new byte[W * 130];
        Arrays.fill(sheet, (byte) 255);
        draw(sheet, A, B, A[0], B[0]);
        draw(sheet, B, C, B[0], C[0]);
        int[] mask = IntStream.range(95, 107).flatMap(x ->
                IntStream.range(65, 85).map(y -> y * W + x))
                .filter(pixel -> (sheet[pixel] & 255) < 135).toArray();
        check("source rail endpoint", sheet, mask, true);
        var missing = sheet.clone();
        for (int x = 40; x < 51; x++)
            paint(missing, x, tip(A, B, x) - 17, 3, 255);
        check("missing upstream rail", missing, mask, false);
        var extendsPastShaft = sheet.clone();
        draw(extendsPastShaft, B, C, C[0] + 1, C[0] + 25);
        check("second beam continues after last shaft", extendsPastShaft, mask, false);
        var outside = Arrays.copyOf(mask, mask.length + 1);
        outside[mask.length] = 51 * W + 99;
        check("unowned dark oval outside beam band", sheet, outside, false);
    }

    private static void check(String description, byte[] image, int[] mask, boolean expected) {
        assertEquals(description, expected, ShortDyadBeamEnd.proves(image, W, GAP, mask, A, B, C));
    }

    private static int tip(int[] a, int[] b, int x) {
        return a[1] + Math.round((b[1] - a[1]) * (x - a[0]) / (float) (b[0] - a[0]));
    }

    private static void draw(byte[] image, int[] a, int[] b, int first, int last) {
        for (int x = first; x <= last; x++) {
            int y = tip(a, b, x);
            paint(image, x, y - 4, 3, 0);
            paint(image, x, y - 17, 3, 0);
        }
    }

    private static void paint(byte[] image, int x, int middle, int radius, int value) {
        for (int y = middle - radius; y <= middle + radius; y++)
            image[y * W + x] = (byte) value;
    }
}
