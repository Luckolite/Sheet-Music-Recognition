// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class TerminalPairedBarCountTest {
    private static final int WIDTH = 900;
    private static final int HEIGHT = 1200;
    private static final int LEFT = 95;
    private static final int RIGHT = 850;
    private static final int LAST_TOP = 840;

    @Test
    public void terminalGrandStaffWithTwoCompleteEighthLanesKeepsOneBar() {
        assertEquals(1, finalCount(false, false));
    }

    @Test
    public void aRealInteriorRuleOrAnIncompleteVoiceRetainsCountFitting() {
        assertEquals(3, finalCount(true, false));
        assertEquals(3, finalCount(false, true));
    }

    private static long finalCount(boolean realBoundary, boolean missingHead) {
        var measures = new ArrayList<MeasureRegion>();
        var labels = new byte[WIDTH * HEIGHT];
        var gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        int[] counts = {3, 1, 3, 1, 3, 1};
        int[] tops = {35, 190, 345, 500, 655, LAST_TOP};
        var numbers = new ArrayList<MeasureNumberReconciler.NumberToken>();
        int start = 20;
        for (int row = 0; row < counts.length; row++) {
            int top = tops[row];
            float y = top / (float) HEIGHT;
            numbers.add(new MeasureNumberReconciler.NumberToken(start, .03f, y, .05f, y + .005f));
            for (int i = 0; i < counts[row]; i++) {
                float left = (LEFT + (RIGHT - LEFT) * i / (float) counts[row]) / WIDTH;
                float right = (LEFT + (RIGHT - LEFT) * (i + 1) / (float) counts[row]) / WIDTH;
                measures.add(new MeasureRegion(left, right, y, (top + (row == 5 ? 300 : 125)) / (float) HEIGHT));
            }
            start += counts[row];
        }
        for (int top : new int[] {875, 1025}) {
            for (int y = top; y <= top + 64; y += 16) fill(gray, labels, LEFT, RIGHT, y, y, 4);
            int beam = top - 10;
            for (int j = 0; j <= 1; j++)
                fill(gray, labels, x(j * 4) + 8, x(j * 4 + 3) + 9, beam, beam + 5, 5);
            for (int i = 0; i < 8; i++) {
                if (top == 1025 && i == 4 && missingHead) continue;
                int y = top + 34 + i % 2 * 8;
                fill(gray, labels, x(i) + 8, x(i) + 9, beam, y, 1);
                for (int dy = -5; dy <= 5; dy++)
                    for (int dx = -9; dx <= 9; dx++)
                        if (dx * dx + 3 * dy * dy <= 81)
                            fill(gray, labels, x(i) + dx, x(i) + dx, y + dy, y + dy, 2);
            }
        }
        fill(gray, labels, RIGHT - 1, RIGHT, 875, 1089, 1);
        if (realBoundary) fill(gray, labels, 347, 347, 875, 1089, 1);
        return MeasureNumberReconciler.reconcile(measures, numbers, List.of(), labels, gray, WIDTH, HEIGHT)
                .stream().filter(bar -> bar.top() == LAST_TOP / (float) HEIGHT).count();
    }

    private static int x(int i) {
        return 145 + 92 * i;
    }

    private static void fill(byte[] gray, byte[] labels, int left, int right, int top, int bottom, int label) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
            Arrays.fill(labels, y * WIDTH + left, y * WIDTH + right + 1, (byte) label);
        }
    }
}
