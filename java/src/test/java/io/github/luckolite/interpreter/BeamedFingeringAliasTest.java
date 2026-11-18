// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BeamedFingeringAliasTest {
    private static final int WIDTH = 442, HEIGHT = 432, GAP = 16, BOTTOM = 220, CENTER = 219;
    private static final MeasureRegion BAR = new MeasureRegion(
            42f / WIDTH, 405f / WIDTH, 122f / HEIGHT, 254f / HEIGHT);

    @Test
    public void fingeringOnTheRuleNeedsTheCurlAboveAndFootBelow() {
        byte[] ink = staff();
        rectangle(ink, CENTER - 7, BOTTOM - 9, CENTER + 6, BOTTOM - 2, (byte) 0);
        rectangle(ink, CENTER - 2, BOTTOM - 6, CENTER + 2, BOTTOM - 3, (byte) 255);
        rectangle(ink, CENTER + 2, BOTTOM + 2, CENTER + 6, BOTTOM + 8, (byte) 0);
        rectangle(ink, CENTER - 7, BOTTOM + 4, CENTER + 6, BOTTOM + 8, (byte) 0);
        var word = word("2", CENTER, BOTTOM - 12, BOTTOM + 15);
        var note = note(1, 2, BOTTOM - 4);
        var words = List.of(word("1", CENTER - 118, BOTTOM - 12, BOTTOM + 15), word,
                word("4", CENTER + 118, BOTTOM - 12, BOTTOM + 15));
        assertTrue(alias(words, note, ink, ink));

        var cut = ink.clone();
        rectangle(cut, CENTER - 9, BOTTOM + 2, CENTER + 9, BOTTOM + 9, (byte) 255);
        assertFalse(alias(words, note, cut, ink));
        assertFalse(alias(words, note, ink, cut));
        assertFalse(alias(List.of(word), note, ink, ink));
    }

    @Test
    public void completeThreeNeedsAWholePrintedGlyphAndNoOwnedShaft() {
        byte[] ink = staff();
        rectangle(ink, CENTER - 7, BOTTOM + 6, CENTER + 7, BOTTOM + 24, (byte) 0);
        rectangle(ink, CENTER - 2, BOTTOM + 9, CENTER + 1, BOTTOM + 13, (byte) 255);
        rectangle(ink, CENTER - 2, BOTTOM + 17, CENTER + 1, BOTTOM + 21, (byte) 255);
        var word = word("3", CENTER, BOTTOM + 2, BOTTOM + 29);
        var note = note(-2, 1, BOTTOM + 19);
        var words = List.of(word("1", CENTER - 128, BOTTOM + 2, BOTTOM + 29), word,
                word("1", CENTER + 128, BOTTOM + 2, BOTTOM + 29));
        assertTrue(alias(words, note, ink, ink));

        var withStem = ink.clone();
        rectangle(withStem, CENTER + 9, BOTTOM + 7, CENTER + 10, BOTTOM + 52, (byte) 0);
        assertFalse(alias(words, note, withStem, ink));
        assertFalse(alias(words, note, ink, withStem));
        assertFalse(alias(words, note.withStemDirection(1), ink, ink));
    }

    private static boolean alias(List<PlayingTechniqueDetector.Word> words, ScoreNoteEvent note,
            byte[] raw, byte[] normalized) {
        return FingeringAnnotationFilter.beamedNumeralAlias(words, BAR, note, raw, normalized, WIDTH, HEIGHT);
    }

    private static ScoreNoteEvent note(int staffStep, int beams, int y) {
        return new ScoreNoteEvent(0, (CENTER - 42f)/(405 - 42f), staffStep, 0, 1, y/(float)HEIGHT,
                false, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE);
    }

    private static PlayingTechniqueDetector.Word word(String text, int center, int top, int bottom) {
        return new PlayingTechniqueDetector.Word(text, (center-12)/(float)WIDTH, top/(float)HEIGHT,
                (center+12)/(float)WIDTH, bottom/(float)HEIGHT);
    }

    private static byte[] staff() {
        var result = new byte[WIDTH * HEIGHT];
        Arrays.fill(result, (byte) 255);
        for (int y = BOTTOM - GAP * 4; y <= BOTTOM; y += GAP)
            rectangle(result, 28, y - 1, 418, y + 1, (byte) 0);
        return result;
    }

    private static void rectangle(byte[] ink, int left, int top, int right, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, value);
    }
}
