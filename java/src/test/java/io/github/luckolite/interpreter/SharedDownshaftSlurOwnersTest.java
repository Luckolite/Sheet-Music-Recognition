package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class SharedDownshaftSlurOwnersTest {
    @Test
    public void theTwoPrintedHeadsShareTheSameDownshaftAndTip() throws Exception {
        int width = 165, height = 228;
        byte[] ink = new byte[width * height];
        Arrays.fill(ink, (byte) 255);
        oval(ink, width, 64, 72);
        oval(ink, width, 65, 128);
        for (int y = 72; y <= 185; y++)
            Arrays.fill(ink, y * width + 53, y * width + 56, (byte) 0);

        var high = new PrintedSlurOwners.GeometryHead(0, 64, 72, 0, 1);
        var low = new PrintedSlurOwners.GeometryHead(1, 65, 128, 0, 1);
        var proof = PrintedSlurOwners.class.getDeclaredMethod(
                "sameObservedDownshaft", byte[].class, int.class, int.class,
                PrintedSlurOwners.Location.class, PrintedSlurOwners.Location.class, float.class);
        proof.setAccessible(true);
        assertTrue((boolean) proof.invoke(null, ink, width, height, high, low, 16f));

        for (int y = 107; y <= 118; y++)
            Arrays.fill(ink, y * width + 52, y * width + 57, (byte) 255);
        assertFalse((boolean) proof.invoke(null, ink, width, height, high, low, 16f));
    }

    private static void oval(byte[] pixels, int width, int cx, int cy) {
        for (int y = -9; y <= 9; y++)
            for (int x = -11; x <= 11; x++)
                if (x * x * 81 + y * y * 121 <= 9801)
                    pixels[(cy + y) * width + cx + x] = 0;
    }
}
