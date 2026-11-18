// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

public class SlideRuleRowTest {
    @Test
    public void rowCoverageUsesStrictInkAndCoverageCutoffs() {
        Random random = new Random(42421);
        for (int width = 1; width <= 160; width++) {
            int offset = width % 11;
            int boundary = (int) (width * .45);
            for (int count = Math.max(0, boundary - 2); count <= Math.min(width, boundary + 2); count++) {
                byte[] gray = new byte[offset + width + 7];
                Arrays.fill(gray, offset, offset + width, (byte) 150);
                Arrays.fill(gray, offset, offset + count, (byte) 149);
                for (int i = width - 1; i > 0; i--) {
                    int j = random.nextInt(i + 1);
                    byte temp = gray[offset + i];
                    gray[offset + i] = gray[offset + j];
                    gray[offset + j] = temp;
                }
                assertEquals(count > width * .45, PackedGrayInk.isSlideRuleRow(gray, offset, width));
            }
        }
    }
}
