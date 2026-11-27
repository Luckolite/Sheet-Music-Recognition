// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class PrintedQuarterTripletsTest {
    private static final int WIDTH = 1024, HEIGHT = 260;
    private static final MeasureRegion BAR =
            new MeasureRegion(50f / WIDTH, 974f / WIDTH, 60f / HEIGHT, 191.2f / HEIGHT);

    @Test
    public void sixQuarterHeadsRecoverTwoCompleteTripletGroups() {
        var image = paper();
        bracket(image, 200);
        bracket(image, 680);
        var source = score();
        var repaired = PrintedQuarterTriplets.recover(
                source, List.of(three(200), three(680)), image, image.clone(), WIDTH, HEIGHT);
        assertTrue(repaired.notes().stream().allMatch(n -> n.tupletDivisor() == 3
                && n.tupletNormalNotes() == 2 && n.unbeamedDurationBeats() == 1));
        assertSame(source.clockContextSource(), repaired.clockContextSource());
    }

    @Test
    public void missingNumberHookOrGapPreservesTheOriginalBar() {
        var image = paper();
        bracket(image, 200);
        bracket(image, 680);
        var source = score();
        var numbers = List.of(three(200), three(680));
        assertSame(source, PrintedQuarterTriplets.recover(
                source, List.of(three(200)), image, image, WIDTH, HEIGHT));
        var missingHook = image.clone();
        missingHook[53 * WIDTH + 784] = (byte) 255;
        missingHook[54 * WIDTH + 784] = (byte) 255;
        missingHook[55 * WIDTH + 784] = (byte) 255;
        assertSame(source, PrintedQuarterTriplets.recover(
                source, numbers, image, missingHook, WIDTH, HEIGHT));
        var unbroken = image.clone();
        horizontal(unbroken, 50, 179, 221, (byte) 0);
        assertSame(source, PrintedQuarterTriplets.recover(
                source, numbers, unbroken, unbroken, WIDTH, HEIGHT));
    }

    @Test
    public void attachedGraceFlagNeedsIndependentBarShaftAndBracketProof() {
        var image = paper();
        var labels = new byte[image.length];
        for (int y = 94; y <= 158; y += 16) horizontal(image, y, 53, 972, (byte) 0);
        for (int y = 94; y <= 158; y++) {
            image[y * WIDTH + 500] = 0;
            labels[y * WIDTH + 500] = OmrMeasurePostProcessor.STEM_OR_REST;
        }
        horizontal(image, 78, 517, 575, (byte) 0);
        for (int y = 79; y <= 89; y++) image[y * WIDTH + 517] = 0;
        for (int y = 161; y <= 167; y++)
            horizontal(labels, y, 493, 502, OmrMeasurePostProcessor.NOTEHEAD);
        for (int y = 153; y <= 164; y++)
            horizontal(labels, y, 525, 541, OmrMeasurePostProcessor.NOTEHEAD);
        var bars = List.of(BAR);
        var result = BracketedGraceBarlines.recover(bars, labels, image, image.clone(), WIDTH, HEIGHT);
        assertEquals(2, result.size());
        assertTrue(result.get(0).right() < 493f / WIDTH);
        assertTrue(result.get(1).left() <= 493f / WIDTH);
        var missingShaft = image.clone();
        for (int y = 103; y < 109; y++) missingShaft[y * WIDTH + 500] = (byte) 255;
        assertSame(bars, BracketedGraceBarlines.recover(bars, labels, image, missingShaft, WIDTH, HEIGHT));
    }

    private static ScorePageInterpretation score() {
        var notes = java.util.stream.IntStream.of(104, 200, 296, 584, 680, 776)
                .mapToObj(x -> new ScoreNoteEvent(0, (x - 50f) / 924, 4, 0, 1,
                        122f / HEIGHT, false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1)
                        .withStemDirection(1)).toList();
        return new ScorePageInterpretation(List.of(BAR), notes, 1,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static PlayingTechniqueDetector.Word three(int x) {
        return new PlayingTechniqueDetector.Word("3", (x - 5f) / WIDTH, 46f / HEIGHT,
                (x + 5f) / WIDTH, 55f / HEIGHT);
    }

    private static void bracket(byte[] image, int x) {
        horizontal(image, 50, x - 104, x - 17, (byte) 0);
        horizontal(image, 50, x + 17, x + 104, (byte) 0);
        for (int y = 51; y <= 60; y++) {
            image[y * WIDTH + x - 104] = 0;
            image[y * WIDTH + x + 104] = 0;
        }
    }

    private static void horizontal(byte[] image, int y, int first, int last, byte color) {
        Arrays.fill(image, y * WIDTH + first, y * WIDTH + last + 1, color);
    }

    private static byte[] paper() {
        var image = new byte[WIDTH * HEIGHT];
        Arrays.fill(image, (byte) 255);
        return image;
    }
}
