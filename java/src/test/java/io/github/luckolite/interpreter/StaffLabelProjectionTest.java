// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class StaffLabelProjectionTest {
    @Test public void croppedProjectionMatchesScalarAcrossByteBoundaries() {
        Random random = new Random(323);
        for (int width = 1; width <= 67; width++) {
            int height = 29, offset = width % 11;
            byte[] labels = new byte[offset + width * height + 13];
            random.nextBytes(labels);
            for (int i = offset; i < offset + width * height; i += 5) labels[i] = 4;
            for (float slope : new float[] {0, -.125f, .0625f, .137f}) {
                int[] expected = new int[height], actual = new int[height];
                int count = 0;
                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++) {
                        if (labels[offset + y * width + x] != 4) continue;
                        count++;
                        int target = Math.round(y - slope * (x - width * .5f));
                        if (target >= 0 && target < height) expected[target]++;
                    }
                assertEquals(count, OmrMeasurePostProcessor.projectStaffLabels(
                        labels, offset, width, height, slope, actual));
                assertArrayEquals(expected, actual);
            }
            byte[] crop = Arrays.copyOfRange(labels, offset, offset + width * height);
            assertEquals(OmrMeasurePostProcessor.estimateStaffSlope(crop, width, height),
                    OmrMeasurePostProcessor.estimateStaffSlope(labels, offset, width, height), 0f);
        }
    }

    @Test public void thinRunProjectionClipsBothEndsAtTheCrop() {
        Random random = new Random(171);
        for (int width = 1; width < 48; width++) {
            int height = 13, stride = width + 6, offset = stride + 3;
            byte[] gray = new byte[stride * (height + 2)];
            for (int i = 0; i < gray.length; i++)
                gray[i] = (byte) (random.nextBoolean() ? 170 : 171);
            for (int thickness = 1; thickness <= 15; thickness++) {
                int[] expected = new int[height];
                for (int x = 0; x < width; x++) {
                    for (int y = 0; y < height; y++) {
                        if ((gray[offset + y * stride + x] & 255) > 170) continue;
                        int first = y;
                        while (y + 1 < height && (gray[offset + (y + 1) * stride + x] & 255) <= 170) y++;
                        if (y - first + 1 <= thickness)
                            for (int row = first; row <= y; row++) expected[row]++;
                    }
                }
                assertArrayEquals(expected, RawStaffLineDetector.thinRowStrength(
                        gray, offset, stride, width, height, thickness));
            }
        }
    }
}
