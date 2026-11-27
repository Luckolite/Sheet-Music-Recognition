// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CrossSystemEndingBracketTest {
    private static final int W = 600, H = 600, GAP = 16;
    private static final List<PlayingTechniqueDetector.Staff> STAFFS = List.of(
            new PlayingTechniqueDetector.Staff(150, 214, GAP, 0, 1),
            new PlayingTechniqueDetector.Staff(329, 393, GAP, 0, 1),
            new PlayingTechniqueDetector.Staff(509, 573, GAP, 0, 1));
    private static final List<MeasureRegion> BARS = List.of(
            measure(60, 288, 145, 220), measure(303, 550, 145, 220),
            measure(80, 281, 324, 399), measure(300, 540, 324, 399),
            measure(100, 500, 504, 580));

    @Test
    public void followsTallOpenAndClosingRoofsAcrossTheSystemBreak() {
        var endings = detect(true);
        assertEquals(2, endings.size());
        assertEquals(List.of(1), endings.get(0).passes());
        assertEquals(1, endings.get(0).start());
        assertEquals(4, endings.get(0).end());
        assertFalse(endings.get(0).openRight());
        assertEquals(List.of(2), endings.get(1).passes());
        assertEquals(4, endings.get(1).start());
        assertEquals(5, endings.get(1).end());
    }

    @Test
    public void unattachedHorizontalStrokeCannotExtendTheFirstEnding() {
        var endings = detect(false);
        assertEquals(2, endings.size());
        assertEquals(2, endings.get(0).end());
        assertTrue(endings.get(0).openRight());
    }

    @Test
    public void fullWidthIntermediateRoofNeedsAClosingHookOnALaterSystem() {
        var bars = List.of(BARS.get(0), BARS.get(1), BARS.get(2), BARS.get(3),
                measure(80, 373, 504, 580), measure(381, 550, 504, 580));
        for (boolean closingHook : List.of(true, false)) {
            byte[] gray = paper();
            rect(gray, 296, 90, 257, 4);
            rect(gray, 296, 90, 2, 54);
            rect(gray, 35, 248, 511, 3);
            rect(gray, 35, 428, 338, 3);
            if (closingHook) rect(gray, 371, 428, 2, 75);
            rect(gray, 378, 428, 175, 3);
            rect(gray, 378, 428, 2, 75);
            var endings = PrintedEndingBrackets.detect(gray, W, H, STAFFS, bars,
                    List.of(word("1.", 302, 103, 318, 122),
                            word("2.", 384, 442, 407, 461)));
            assertEquals(2, endings.size());
            assertEquals(closingHook ? 5 : 2, endings.get(0).end());
            assertEquals(!closingHook, endings.get(0).openRight());
        }
    }

    @Test
    public void condensedSerifSelectorHasItsOwnCapFootAndPeriod() {
        for (boolean hasCap : List.of(true, false)) {
            byte[] gray = paper();
            rect(gray, 296, 90, 257, 3);
            rect(gray, 296, 90, 2, 54);
            rect(gray, 307, 103, 3, 20);
            if (hasCap) rect(gray, 306, 104, 1, 3);
            rect(gray, 306, 120, 1, 3);
            rect(gray, 318, 120, 3, 3);
            var endings = PrintedEndingBrackets.detect(gray, W, H, STAFFS, BARS, List.of());
            if (hasCap) {
                assertEquals(1, endings.size());
                assertEquals(List.of(1), endings.get(0).passes());
            } else {
                assertTrue(endings.isEmpty());
            }
        }
    }

    @Test
    public void fusedChordTextNeedsTheSeparatePrintedSelector() {
        byte[] gray = paper();
        rect(gray, 296, 90, 257, 4);
        rect(gray, 296, 90, 2, 54);
        int x = 307, y = 104;
        rect(gray, x + 2, y, 3, 21);
        rect(gray, x, y + 2, 3, 2);
        rect(gray, x, y + 20, 6, 2);
        rect(gray, 319, y + 18, 4, 4);
        rect(gray, 330, y + 5, 3, 18);
        var joined = List.of(word("1.D", 304, 100, 346, 130));
        assertEquals(1, PrintedEndingBrackets.detect(gray, W, H, STAFFS, BARS, joined).size());
        assertTrue(PrintedEndingBrackets.detect(gray, W, H, STAFFS, BARS,
                List.of(word("2.D", 304, 100, 346, 130))).isEmpty());
    }

    @Test
    public void separatelyRecognizedChordDoesNotHideThePrintedEndingNumber() {
        byte[] gray = paper();
        rect(gray, 296, 90, 257, 4);
        rect(gray, 296, 90, 2, 54);
        int x = 307, y = 104;
        rect(gray, x + 2, y, 3, 21);
        rect(gray, x, y + 2, 3, 2);
        rect(gray, x, y + 20, 6, 2);
        rect(gray, 319, y + 18, 7, 4);
        rect(gray, 330, y + 5, 3, 18);
        var endings = PrintedEndingBrackets.detect(gray, W, H, STAFFS, BARS,
                List.of(word("D", 329, 107, 342, 131)));
        assertEquals(1, endings.size());
        assertEquals(List.of(1), endings.get(0).passes());
        assertTrue(PrintedEndingBrackets.detect(gray, W, H, STAFFS, BARS, List.of()).isEmpty());
    }

    @Test
    public void followsTheClosingHookToAnAdjacentSecondEndingMidSystem() {
        var endings = detectMidSystem(378);
        assertEquals(2, endings.size());
        assertEquals(1, endings.get(0).start());
        assertEquals(3, endings.get(0).end());
        assertFalse(endings.get(0).openRight());
        assertEquals(List.of(2), endings.get(1).passes());
        assertEquals(3, endings.get(1).start());
    }

    @Test
    public void aDistantSecondEndingDoesNotOwnTheClosingHook() {
        var endings = detectMidSystem(399);
        assertEquals(2, endings.size());
        assertEquals(2, endings.get(0).end());
        assertTrue(endings.get(0).openRight());
    }

    @Test
    public void fullBarRoofStartingAboveTheClefBindsToTheFirstMeasure() {
        var endings = leftHeaderRoof(25, 350);
        assertEquals(1, endings.size());
        assertEquals(0, endings.get(0).start());
        assertEquals(1, endings.get(0).end());
        assertEquals(List.of(2), endings.get(0).passes());
    }

    @Test
    public void distantOrTruncatedRoofsCannotBorrowTheOpeningMeasure() {
        assertTrue(leftHeaderRoof(8, 350).isEmpty());
        assertTrue(leftHeaderRoof(25, 292).isEmpty());
    }

    private static List<PrintedEndingBrackets.Mark> leftHeaderRoof(int left, int right) {
        byte[] gray = paper();
        rect(gray, left, 248, right - left + 1, 3);
        rect(gray, left, 248, 2, 75);
        var staff = List.of(STAFFS.get(1));
        var bars = List.of(measure(125, 350, 324, 399), measure(363, 550, 324, 399));
        return PrintedEndingBrackets.detect(gray, W, H, staff, bars,
                List.of(word("2.", left + 5, 262, left + 20, 281)));
    }

    private static List<PrintedEndingBrackets.Mark> detectMidSystem(int secondHook) {
        byte[] gray = paper();
        rect(gray, 296, 90, 257, 6);
        rect(gray, 296, 90, 2, 54);
        rect(gray, 35, 248, 338, 3);
        rect(gray, 371, 248, 2, 75);
        rect(gray, secondHook, 248, 552 - secondHook, 3);
        rect(gray, secondHook, 248, 2, 74);
        var bars = List.of(BARS.get(0), BARS.get(1),
                measure(80, 373, 324, 399), measure(381, 550, 324, 399));
        return PrintedEndingBrackets.detect(gray, W, H, STAFFS, bars,
                List.of(word("1.", 302, 103, 318, 122),
                        word("2.", secondHook + 5, 262, secondHook + 20, 281)));
    }

    private static List<PrintedEndingBrackets.Mark> detect(boolean closingHook) {
        byte[] gray = paper();
        rect(gray, 296, 90, 257, 6);
        rect(gray, 296, 90, 2, 54);
        rect(gray, 35, 248, 511, 3);
        if (closingHook) rect(gray, 544, 248, 2, 75);
        rect(gray, 40, 366, 463, 3);
        rect(gray, 40, 366, 2, 136);
        return PrintedEndingBrackets.detect(gray, W, H, STAFFS, BARS,
                List.of(word("1.", 302, 103, 318, 122), word("2.", 46, 378, 63, 400)));
    }

    private static byte[] paper() {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        return gray;
    }

    private static MeasureRegion measure(int left, int right, int top, int bottom) {
        return new MeasureRegion(left / (float) W, right / (float) W, top / (float) H, bottom / (float) H);
    }

    private static PlayingTechniqueDetector.Word word(String text, int left, int top, int right, int bottom) {
        return new PlayingTechniqueDetector.Word(text, left / (float) W, top / (float) H,
                right / (float) W, bottom / (float) H);
    }

    private static void rect(byte[] gray, int left, int top, int w, int h) {
        for (int y = top; y < top + h; y++) Arrays.fill(gray, y * W + left, y * W + left + w, (byte) 0);
    }
}
