// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedSixFourTripletBeamTest {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 240;
    private static final float GAP = 16;

    @Test
    public void aNumberBelowTheHeadsVetoesAnUnnumberedBeamProjection() throws Exception {
        byte[] plane = whitePaper();
        assertFalse(numbered(plane, List.of()));
        assertFalse(numbered(plane, List.of(word("4", 80, 169))));
        assertTrue(numbered(plane, List.of(word("3", 80, 169))));
        assertFalse(numbered(plane, List.of(word("3", 123, 169))));
    }

    @Test
    public void anIsolatedThreeCanProtectThePrintWithoutAnOcrWord() throws Exception {
        byte[] plane = whitePaper();
        String[] digit = {
            ".###.",
            "...##",
            "....#",
            "...##",
            ".###.",
            "...##",
            "....#",
            "....#",
            "...##",
            ".###."
        };
        for (int y = 0; y < digit.length; y++) {
            for (int x = 0; x < digit[y].length(); x++) {
                if (digit[y].charAt(x) != '#') continue;
                for (int dy = 0; dy < 2; dy++) {
                    Arrays.fill(plane, (155 + 2 * y + dy) * WIDTH + 76 + 2 * x,
                            (155 + 2 * y + dy) * WIDTH + 78 + 2 * x, (byte) 0);
                }
            }
        }
        assertTrue(numbered(plane, List.of()));
    }

    private static byte[] whitePaper() {
        byte[] result = new byte[WIDTH * HEIGHT];
        Arrays.fill(result, (byte) 255);
        return result;
    }

    private static PlayingTechniqueDetector.Word word(String text, int x, int y) {
        return new PlayingTechniqueDetector.Word(text, (float) (x - 4) / WIDTH,
                (float) (y - 6) / HEIGHT, (float) (x + 4) / WIDTH, (float) (y + 6) / HEIGHT);
    }

    private static boolean numbered(byte[] plane, List<PlayingTechniqueDetector.Word> words)
            throws Exception {
        var proof = WrappedEighthTriplets.class.getDeclaredMethod("localPrintedThree", byte[].class,
                int.class, int.class, float.class, float.class, float.class, float.class,
                UnnumberedTripletInk.Rows.class, List.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, plane, WIDTH, HEIGHT, 80f, 75f, 128f, GAP,
                new UnnumberedTripletInk.Rows(20, 210), words);
    }
}
