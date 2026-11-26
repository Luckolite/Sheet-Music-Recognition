// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class InlineMeterLowerDigitTest {
    private static final int WIDTH = 350, HEIGHT = 200, TOP = 50, GAP = 16;
    private static final List<MeasureRegion> MEASURES = List.of(
            new MeasureRegion(20f / WIDTH, 112f / WIDTH, 18f / HEIGHT, 149f / HEIGHT),
            new MeasureRegion(128f / WIDTH, 320f / WIDTH, 18f / HEIGHT, 149f / HEIGHT));
    private static final ScoreNoteEvent ALIAS = new ScoreNoteEvent(
            1, (135f - 128) / 192, 3, 0, 1, 90f / HEIGHT, false, 0, 0,
            ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 2).withStemDirection(1);
    private static final ScoreNoteEvent FIRST = new ScoreNoteEvent(
            1, (186f - 128) / 192, 8, 0, 1, 50f / HEIGHT, false, 0, 0,
            ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1).withStemDirection(-1);

    @Test
    public void sharesTheLowerDigitOnlyWhenTheInlineDividerAndWholeMeterAreProved() throws Exception {
        assertTrue(InlineMeterLowerDigit.candidate(ALIAS));
        assertFalse(InlineMeterLowerDigit.candidate(ALIAS.withStemDirection(-1)));
        var printed = meter("6", true);
        assertEquals(new ScoreMeterChange(1, 6, 8), recover(printed, printed));
        assertNull(recover(printed, meter("6", false)));
        assertNull(recover(printed, meter("9", true)));
    }

    private static byte[] meter(String numerator, boolean divider) throws Exception {
        var fixture = MeterFontMatcherTest.class.getDeclaredMethod(
                "signature", String.class, String.class, int.class);
        fixture.setAccessible(true);
        int[] signature = (int[]) fixture.invoke(null, numerator, "8", 32);
        var result = staff(divider);
        for (int y = 0; y < signature.length / 32; y++)
            for (int x = 0; x < 32; x++) {
                int at = (47 + y) * WIDTH + 123 + x;
                result[at] = (byte) Math.min(result[at] & 255, signature[y * 32 + x] & 255);
            }
        return result;
    }
    private static ScoreMeterChange recover(byte[] gray, byte[] source) {
        return InlineMeterLowerDigit.recover(ALIAS, List.of(ALIAS, FIRST), MEASURES,
                new byte[gray.length], gray, source, WIDTH, HEIGHT);
    }

    private static byte[] staff(boolean divider) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int i = 0; i <= 4; i++) {
            int y = TOP + i * GAP;
            Arrays.fill(gray, y * WIDTH + 15, y * WIDTH + 335, (byte) 0);
        }
        if (divider)
            for (int y = TOP; y <= TOP + 4 * GAP; y++) gray[y * WIDTH + 120] = 0;
        return gray;
    }
}
