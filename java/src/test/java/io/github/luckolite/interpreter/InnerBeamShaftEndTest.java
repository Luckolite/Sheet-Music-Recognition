// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import java.util.stream.IntStream;
import org.junit.Test;

/** Synthetic positive and negative controls for a double-beam endpoint. */
public final class InnerBeamShaftEndTest {
    private static final int WIDTH = 220;
    private static final int HEIGHT = 130;
    private static final float GAP = 16;
    private static final int[] EARLIER = {34, 99, 1};
    private static final int[] PREVIOUS = {77, 93, 1};
    private static final int[] TERMINAL = {127, 87, 1};

    @Test
    public void requiresTwoClosingRailsAndAContainedMask() {
        byte[] fixture = new byte[WIDTH * HEIGHT];
        Arrays.fill(fixture, (byte) 255);
        drawRails(fixture, EARLIER, PREVIOUS, false);
        drawRails(fixture, PREVIOUS, TERMINAL, true);
        int[] fragment = IntStream.range(109, 127).boxed()
                .flatMapToInt(x -> IntStream.range(76, 84).map(y -> y * WIDTH + x))
                .filter(pixel -> (fixture[pixel] & 255) < 140)
                .toArray();
        verify("two rails meeting borrowed shaft", true, fixture, fragment);
        var single = fixture.clone();
        for (int x = 41; x < 71; x++) {
            int y = tip(EARLIER, PREVIOUS, x) - 17;
            paint(single, x, y, 5, 255);
        }
        verify("absent upstream inner rail", false, single, fragment);
        var continuing = fixture.clone();
        for (int x = 128; x < 156; x++) {
            int y = tip(PREVIOUS, TERMINAL, x);
            paint(continuing, x, y - 5, 3, 0);
            paint(continuing, x, y - 17, 3, 0);
        }
        verify("rail continues beyond the shaft", false, continuing, fragment);
        var outlying = Arrays.copyOf(fragment, fragment.length + 1);
        outlying[fragment.length] = 62 * WIDTH + 123;
        verify("head mask extends outside the beam", false, fixture, outlying);
    }

    private static void drawRails(byte[] image, int[] a, int[] b, boolean bridge) {
        for (int x = a[0]; x <= b[0]; x++) {
            int y = tip(a, b, x);
            paint(image, x, y - 5, 3, 0);
            paint(image, x, y - 17, 3, 0);
            if (bridge && x > 111) paint(image, x, y - 11, 6, 0);
        }
    }

    private static void verify(String label, boolean expected, byte[] raw, int[] fragment) {
        boolean actual = InnerBeamShaftEnd.proves(raw, WIDTH, GAP, fragment,
                EARLIER, PREVIOUS, TERMINAL);
        assertEquals(label, expected, actual);
    }

    private static int tip(int[] a, int[] b, int x) {
        return a[1] + Math.round((b[1] - a[1]) * (x - a[0]) / (float) (b[0] - a[0]));
    }

    private static void paint(byte[] raw, int x, int center, int radius, int color) {
        for (int y = center - radius; y <= center + radius; y++)
            raw[y * WIDTH + x] = (byte) color;
    }
}
