package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HandwrittenThreeHeadInkTest {
    @Test
    public void provesBothCurlsAndRejectsAnIncompleteMark() {
        int width = 220, height = 180;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        rect(gray, width, 83, 89, 47, 63, 0);
        rect(gray, width, 108, 115, 47, 97, 0);
        rect(gray, width, 84, 90, 93, 97, 0);
        rect(gray, width, 93, 106, 104, 109, 0);
        assertTrue(HandwrittenThreeHeadInk.matches(gray, width, height, 100, 100, 12));

        rect(gray, width, 83, 89, 47, 63, 255);
        assertFalse(HandwrittenThreeHeadInk.matches(gray, width, height, 100, 100, 12));
        rect(gray, width, 83, 89, 47, 63, 0);
        rect(gray, width, 96, 103, 113, 122, 0);
        assertFalse(HandwrittenThreeHeadInk.matches(gray, width, height, 100, 100, 12));
    }

    private static void rect(byte[] gray, int width, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * width + left, y * width + right + 1, (byte) tone);
    }
}
