// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PrintedQuarterDyadAccidentalsTest {
    private static final int WIDTH = 360;
    private static final int HEIGHT = 260;
    private static final int LEFT = 129;
    private static final int RIGHT = 279;
    private record Page(byte[] gray, byte[] labels) {}

    @Test
    public void overlappingAccidentalBoxesUseSeparateInkCores() {
        assertTrue(proved(twoSigns()));
    }

    @Test
    public void missingBowlLinkedSignsOrAnExtraAttackAbstain() {
        var missingBowl = twoSigns();
        paint(missingBowl.gray(), 140, 161, 109, 132, 255);
        assertFalse(proved(missingBowl));

        var connected = twoSigns();
        mark(connected, 145, 153, 93, 95);
        assertFalse(proved(connected));

        var anotherHead = twoSigns();
        paint(anotherHead.gray(), 211, 226, 105, 115, 0);
        paint(anotherHead.gray(), 223, 225, 83, 110, 0);
        assertFalse(proved(anotherHead));
    }

    @Test
    public void twoFlatsWithADoubleRuleCloseTheSystem() {
        assertTrue(closed(twoFlats(false)));
    }

    @Test
    public void singleRuleNeedsTheStaffToEndAtTheCadence() {
        assertTrue(closed(twoFlats(true)));
        var continued = twoFlats(true);
        for (int y = 80; y <= 144; y += 16) paint(continued.gray(), 277, 310, y, y + 1, 0);
        assertFalse(closed(continued));
    }

    @Test
    public void staffEndDoesNotRescueAnUnownedFlatOrMissingBarline() {
        var missingFlat = twoFlats(false);
        paint(missingFlat.labels(), 150, 169, 49, 87, 0);
        assertFalse(closed(missingFlat));

        var missingRule = twoFlats(false);
        paint(missingRule.gray(), 274, 284, 78, 145, 255);
        for (int y = 80; y <= 144; y += 16) paint(missingRule.gray(), 274, 284, y, y + 1, 0);
        assertFalse(closed(missingRule));
    }

    private static boolean closed(Page image) {
        var region =
                new MeasureRegion(
                        LEFT / (float) WIDTH, RIGHT / (float) WIDTH, 45f / HEIGHT, 177f / HEIGHT);
        var next = new MeasureRegion(50f / WIDTH, 325f / WIDTH, 183f / HEIGHT, 255f / HEIGHT);
        var upper = note(182, 80, 8, ScoreNoteEvent.ACCIDENTAL_FLAT);
        var lower = note(183, 120, 3, ScoreNoteEvent.ACCIDENTAL_FLAT);
        return PrintedQuarterDyadAccidentals.provesClosedTripleSystemEnd(
                image.labels(), image.gray(), WIDTH, HEIGHT, region, next, List.of(upper, lower));
    }

    private static Page twoFlats(boolean single) {
        var image = twoSigns();
        paint(image.gray(), 152, 166, 49, 105, 255);
        paint(image.labels(), 152, 166, 49, 105, 0);
        for (int y = 80; y <= 96; y += 16) paint(image.gray(), 152, 166, y, y + 1, 0);
        mark(image, 153, 156, 52, 88);
        mark(image, 153, 166, 72, 78);
        mark(image, 153, 164, 79, 82);
        mark(image, 153, 160, 83, 86);
        if (single) {
            paint(image.gray(), 277, 284, 78, 145, 255);
        }
        return image;
    }

    private static boolean proved(Page image) {
        var bar = new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH,
                45f / HEIGHT, 165f / HEIGHT);
        var next = new MeasureRegion(50f / WIDTH, 325f / WIDTH,
                175f / HEIGHT, 247f / HEIGHT);
        var natural = note(182, 80, 8, ScoreNoteEvent.ACCIDENTAL_NATURAL);
        var flat = note(183, 120, 3, ScoreNoteEvent.ACCIDENTAL_FLAT);
        return PrintedQuarterDyadAccidentals.proves(
                image.labels(), image.gray(), WIDTH, HEIGHT, bar, next, List.of(natural, flat),
                List.of(new PlayingTechniqueDetector.Staff(80, 144, 16, 0, 1)));
    }

    private static ScoreNoteEvent note(int x, int y, int step, int accidental) {
        return new ScoreNoteEvent(
                0, (x - LEFT) / (float) (RIGHT - LEFT), step, 0, 1, y / (float) HEIGHT,
                false, 0, 0, accidental, 1, 1, 0, 0, 30, false, 0, false, 0, 0, 1, -1);
    }

    private static Page twoSigns() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        byte[] labels = new byte[gray.length];
        var page = new Page(gray, labels);
        for (int y = 80; y <= 144; y += 16) paint(gray, 40, 284, y, y + 1, 0);
        paint(gray, 120, 122, 80, 145, 0);
        paint(gray, 274, 276, 80, 145, 0);
        paint(gray, 282, 284, 80, 145, 0);
        paint(gray, 175, 176, 79, 151, 0);
        paint(gray, 175, 190, 77, 84, 0);
        paint(gray, 176, 190, 117, 124, 0);

        mark(page, 152, 155, 54, 93);
        mark(page, 163, 166, 67, 105);
        mark(page, 152, 166, 67, 73);
        mark(page, 152, 166, 84, 90);
        mark(page, 143, 146, 90, 128);
        mark(page, 143, 158, 110, 116);
        mark(page, 143, 156, 117, 120);
        mark(page, 143, 152, 121, 125);
        return page;
    }

    private static void mark(Page page, int left, int right, int top, int bottom) {
        paint(page.gray(), left, right, top, bottom, 0);
        paint(page.labels(), left, right, top, bottom, OmrMeasurePostProcessor.CLEF_OR_KEY);
    }

    private static void paint(
            byte[] a, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(a, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
        }
    }
}
