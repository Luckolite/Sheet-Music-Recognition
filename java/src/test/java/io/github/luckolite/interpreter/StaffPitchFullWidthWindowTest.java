// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original procedural crops preserve the detector's unsupported-span and invalid-input contracts. */
public class StaffPitchFullWidthWindowTest {
    @Test
    public void packedStaffContrastMatchesEveryShadeAndFlankBoundary() throws Exception {
        var count = StaffPitchTrack.class.getDeclaredMethod(
                "contrastCount", byte[].class, int.class, int.class, int.class);
        count.setAccessible(true);
        byte[] gray = new byte[96];
        for (int shade = 0; shade < 256; shade++) {
            for (int k = 0; k < 32; k++) {
                int center = (shade + 73 * k) & 255;
                gray[32 + k] = (byte) center;
                gray[k] = (byte) Math.min(255, center + 11 + k % 3);
                gray[64 + k] = (byte) Math.min(255, center + 13 - k % 3);
            }
            for (int size : new int[] {7, 8, 9, 16, 25}) {
                int expected = 0;
                for (int k = 3; k < 3 + size; k++) {
                    int center = gray[32 + k] & 255;
                    if (center <= 225 && (gray[k] & 255) >= center + 12
                            && (gray[64 + k] & 255) >= center + 12) expected++;
                }
                assertEquals(expected, ((Integer) count.invoke(null, gray, 35, size, 32)).intValue());
            }
        }
        Arrays.fill(gray, (byte) 255);
        assertEquals(0, ((Integer) count.invoke(null, gray, 0, 32, 32)).intValue());
    }

    @Test
    public void narrowCompleteRulesCannotInventAHorizontalTrack() {
        int width = 80, height = 200;
        float gap = 8, top = 60, bottom = 92;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 235);
        for (int x = 0; x < width; x++)
            for (int line = 0; line < 5; line++) gray[((int) top + line * 8) * width + x] = 45;
        byte[] before = gray.clone();
        assertNull(StaffPitchTrack.detect(gray, width, height, top, bottom, gap));
        assertNull(StaffPitchTrack.detectForSymbols(gray, width, height, top, bottom, gap));
        assertNull(StaffPitchTrack.detectForMeasures(gray, width, height, top, bottom, gap));
        assertArrayEquals(before, gray);
    }

    @Test
    public void invalidRastersKeepTheirExistingRefusalsAndExceptions() {
        assertNull(StaffPitchTrack.detect(null, 80, 200, 60, 108, 12));
        assertThrows(
                ArrayIndexOutOfBoundsException.class,
                () -> StaffPitchTrack.detect(new byte[1], 80, 200, 60, 108, 12));
        assertThrows(
                NegativeArraySizeException.class,
                () -> StaffPitchTrack.detect(new byte[0], -1, 200, 60, 108, 12));
    }
}
