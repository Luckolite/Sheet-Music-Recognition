// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedSixteenthPairBeforeHalfTest {
    private static final int WIDTH = 250;
    private static final int HEIGHT = 200;
    private static final int TOP = 60;
    private static final int GAP = 16;
    private static final int BOTTOM = TOP + 4 * GAP;
    private static final int RULE = 120;

    @Test
    public void requiresOneIndependentRuleBoundedByTheStaff() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int line = TOP; line <= BOTTOM; line += GAP)
            Arrays.fill(gray, line * WIDTH + 24, line * WIDTH + WIDTH - 24, (byte) 0);

        assertFalse(ownsSeparator(gray));

        for (int y = TOP; y <= BOTTOM; y++)
            Arrays.fill(gray, y * WIDTH + RULE - 1, y * WIDTH + RULE + 3, (byte) 0);
        assertTrue(ownsSeparator(gray));

        byte[] twoRules = gray.clone();
        for (int y = TOP; y <= BOTTOM; y++)
            Arrays.fill(twoRules, y * WIDTH + RULE + 5, y * WIDTH + RULE + 8, (byte) 0);
        assertFalse(ownsSeparator(twoRules));

        for (int y = TOP - 18; y < TOP; y++)
            Arrays.fill(gray, y * WIDTH + RULE - 1, y * WIDTH + RULE + 3, (byte) 0);
        assertFalse(ownsSeparator(gray));
    }

    @Test
    public void sameStaffAlternativeRequiresTheFollowingPrintedTripleMeter() throws Exception {
        assertTrue(followingTripleMeter(InlineTripleMeterProofTest.signature('3', '4', true)));
        assertFalse(followingTripleMeter(InlineTripleMeterProofTest.signature('2', '4', true)));
        assertFalse(followingTripleMeter(InlineTripleMeterProofTest.signature('4', '4', true)));
    }

    private static boolean followingTripleMeter(byte[] gray) throws Exception {
        int width = 320, height = 180, top = 54, gap = 16;
        var labels = new byte[gray.length];
        for (int y = top; y <= top + gap * 4; y++) {
            for (int x = 10; x < width - 10; x++) {
                int at = y * width + x;
                if ((gray[at] & 255) >= 180) continue;
                if ((y - top) % gap == 0) labels[at] = OmrMeasurePostProcessor.STAFF;
                else if (x >= 123 && x <= 159) labels[at] = OmrMeasurePostProcessor.CLEF_OR_KEY;
                else if (x >= 108 && x <= 116) labels[at] = OmrMeasurePostProcessor.STEM_OR_REST;
            }
        }
        float topOfRegion = (top - 30f) / height;
        float bottomOfRegion = (top + gap * 4 + 30f) / height;
        var half = new MeasureRegion(44f / width, 101f / width, topOfRegion, bottomOfRegion);
        var following = new MeasureRegion(118f / width, 291f / width, topOfRegion, bottomOfRegion);
        var gate = ClosedSixteenthPairBeforeHalf.class.getDeclaredMethod(
                "inlineTripleMeter",
                byte[].class, byte[].class, int.class, int.class, List.class, List.class);
        gate.setAccessible(true);
        return (boolean) gate.invoke(null, labels, gray, width, height, List.of(half, following), List.of());
    }

    private static boolean ownsSeparator(byte[] gray) {
        var staff = new PlayingTechniqueDetector.Staff(TOP, BOTTOM, GAP, 0, 1);
        return ClosedSixteenthPairBeforeHalf.ownedSeparator(gray, WIDTH, HEIGHT, staff, RULE);
    }
}
