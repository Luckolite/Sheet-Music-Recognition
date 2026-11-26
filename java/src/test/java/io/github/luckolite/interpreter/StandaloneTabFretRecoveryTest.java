// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class StandaloneTabFretRecoveryTest {
    private static final int WIDTH = 500, HEIGHT = 220, TOP = 45, GAP = 24;
    private static final int STRING_Y = TOP + GAP;

    private static byte[] page() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int string = 0; string < 6; string++) {
            int row = (TOP + string * GAP) * WIDTH;
            Arrays.fill(gray, row + 20, row + 480, (byte) 190);
        }
        return gray;
    }

    private static void digitInk(byte[] gray, int x, int y) {
        for (int row = y - 9; row < y + 9; row++)
            for (int col = x; col < x + 12; col++)
                if (row < y - 6 || row >= y + 6 || col < x + 2 || col >= x + 10)
                    gray[row * WIDTH + col] = 0;
    }

    private static TablatureDecoder.Word word(String text, int left, int right) {
        return new TablatureDecoder.Word(text, left / (float) WIDTH, (STRING_Y - 11f) / HEIGHT,
                right / (float) WIDTH, (STRING_Y + 11f) / HEIGHT);
    }

    private static TablatureDecoder.Staff staff(float standardTop) {
        return new TablatureDecoder.Staff(TOP, GAP, standardTop, List.of(), List.of(20f, 480f));
    }

    private static List<Integer> recover(
            byte[] gray, TablatureDecoder.Staff staff, List<TablatureDecoder.Word> words)
            throws Exception {
        var read = TablatureDecoder.withWords(List.of(staff), words, WIDTH, HEIGHT);
        return TabFretRaster.supplement(gray, WIDTH, HEIGHT, read, words, null)
                .get(0).frets().stream().map(TablatureDecoder.Fret::fret).toList();
    }

    @Test public void looseRowBoxRecoversTwoFretsAndAnIndependentOpenString() throws Exception {
        byte[] gray = page();
        digitInk(gray, 100, STRING_Y);
        digitInk(gray, 145, STRING_Y);
        digitInk(gray, 210, STRING_Y + GAP);
        digitInk(gray, 260, STRING_Y);
        assertEquals(List.of(0, 2, 0, 3),
                recover(gray, staff(-1), List.of(word("0 2", 97, 160), word("3", 259, 273))));
        assertEquals(List.of(3),
                recover(gray, staff(5), List.of(word("0 2", 97, 160), word("3", 259, 273))));
    }

    @Test public void separatedAttacksReplaceJoinedDigitsButACompactFretStaysWhole()
            throws Exception {
        byte[] gray = page();
        digitInk(gray, 100, STRING_Y);
        digitInk(gray, 127, STRING_Y);
        digitInk(gray, 211, STRING_Y);
        digitInk(gray, 225, STRING_Y);
        assertEquals(List.of(0, 1, 22),
                recover(gray, staff(-1), List.of(word("01", 97, 141), word("22", 210, 238))));
    }
}
