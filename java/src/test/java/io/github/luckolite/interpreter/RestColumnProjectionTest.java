// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

public class RestColumnProjectionTest {
    @Test public void changingBandsPreserveRowStatisticsAndExactColumnCounts() {
        int width = 139, height = 35;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 170);
        Random random = new Random(11419);
        int[] dark = new int[height], longest = new int[height];
        var projection = new RestColumnProjection(gray, width, height, dark);
        for (int y = 0; y < height; y++) {
            int count = 0, length = 0, maximum = 0;
            for (int x = 0; x < width; x++) {
                boolean ink = switch (y % 5) {
                    case 0 -> false;
                    case 1 -> x == width - 1;
                    case 2 -> x % 2 == 0;
                    case 3 -> true;
                    default -> random.nextBoolean();
                };
                if (ink) {
                    gray[y * width + x] = (byte) 169;
                    count++;
                    maximum = Math.max(maximum, ++length);
                } else length = 0;
            }
            projection.readRow(y, longest);
            assertEquals(count, dark[y]);
            assertEquals(maximum, longest[y]);
        }
        for (int trial = 0; trial < 300; trial++) {
            int top = random.nextInt(height), bottom = top + random.nextInt(height - top);
            boolean[] mask = new boolean[bottom - top + 1];
            int[] expected = new int[width];
            for (int y = top; y <= bottom; y++) {
                mask[y - top] = random.nextBoolean();
                if (!mask[y - top])
                    for (int x = 0; x < width; x++)
                        if ((gray[y * width + x] & 255) < 170) expected[x]++;
            }
            int[] counts = projection.restBandCounts(top, bottom, mask);
            if (Arrays.stream(expected).sum() < 2) assertNull(counts);
            else assertArrayEquals(expected, counts);
        }
    }
}
