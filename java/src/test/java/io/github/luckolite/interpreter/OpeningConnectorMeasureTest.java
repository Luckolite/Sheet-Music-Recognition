// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import org.junit.Test;

public final class OpeningConnectorMeasureTest {
    private static final int WIDTH = 540, HEIGHT = 360, GAP = 16;
    private static final int BEFORE = 80, CONNECTOR = 144, CLOSE = 475;
    private static final int[] STAFF_TOPS = {65, 217};

    @Test
    public void emptyAreaToTheLeftOfAJoinedStaffHasNoMeasure() {
        assertEquals(1, measures(false));
    }

    @Test
    public void staffRulesAndAPlacedRestKeepTheShortOpeningMeasure() {
        assertEquals(2, measures(true));
    }

    private static int measures(boolean printedOpening) {
        byte[] gray = new byte[WIDTH * HEIGHT], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int top : STAFF_TOPS) {
            int start = printedOpening || top == STAFF_TOPS[0] ? BEFORE : CONNECTOR;
            for (int line = 0; line < 5; line++) {
                int y = top + line * GAP;
                for (int x = start; x <= CLOSE; x++) {
                    labels[y * WIDTH + x] = OmrMeasurePostProcessor.STAFF;
                    if (printedOpening || x >= CONNECTOR) gray[y * WIDTH + x] = 0;
                }
            }
            for (int x : new int[] {start, CONNECTOR, CLOSE})
                for (int y = top; y <= top + 4 * GAP; y++) {
                    labels[y * WIDTH + x] = OmrMeasurePostProcessor.STEM_OR_REST;
                    if (printedOpening || x >= CONNECTOR) gray[y * WIDTH + x] = 0;
                }
            if (printedOpening)
                for (int y = top + 2 * GAP; y < top + 2 * GAP + 5; y++)
                    for (int x = BEFORE + 24; x < BEFORE + 37; x++) {
                        gray[y * WIDTH + x] = 0;
                        labels[y * WIDTH + x] = OmrMeasurePostProcessor.STEM_OR_REST;
                    }
        }
        for (int y = STAFF_TOPS[0]; y <= STAFF_TOPS[1] + 4 * GAP; y++) {
            gray[y * WIDTH + CONNECTOR] = 0;
            gray[y * WIDTH + CLOSE] = 0;
            if (printedOpening) gray[y * WIDTH + BEFORE] = 0;
        }
        return OmrMeasurePostProcessor.process(labels, gray, WIDTH, HEIGHT).size();
    }
}
