// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedDoubleHarmonicTerminalQuarterTest {
    private static final int WIDTH = 380, HEIGHT = 240, GAP = 16;
    private static final int TOP = 88, BOTTOM = TOP + 4 * GAP, X = 200;
    private static final int UPPER = BOTTOM - 5 * GAP / 2, LOWER = BOTTOM;
    private static final int LEFT = 165, RIGHT = 266, BAR = 2;

    @Test
    public void sharedDownshaftAndTwoTouchDiamondsCloseOneQuarter() {
        var dir = score(8);
        var paper = source();
        var decoded = close(dir, paper, paper);
        assertEquals(1, decoded.measures().get(BAR).cadenceQuarterBeats());
        assertEquals(dir.measures().subList(0, BAR), decoded.measures().subList(0, BAR));
        for (int i = 0; i < 2; i++) {
            var note = decoded.notes().get(i);
            assertEquals(0, note.beamCount());
            assertEquals(1, note.unbeamedDurationBeats(), 0);
            assertEquals(0, note.boundaryTies());
            assertEquals(2, note.octaveShift());
        }
    }

    @Test
    public void printedOutgoingCurveAndUntypedIncomingMarkerKeepOriginal() {
        var dir = score(8);
        var paper = source();
        var arc = paper.clone();
        for (int x = X + 11; x <= X + 54; x++) {
            int y = UPPER + 3 + Math.round(10f * (float)Math.sin(Math.PI * (x - X - 11) / 43));
            paint(arc, x, x, y, y + 1, 0);
        }
        assertSame(dir, close(dir, paper, arc));
        assertSame(dir, close(dir, arc, paper));
        var incoming = score(2);
        assertSame(incoming, close(incoming, paper, paper));
    }

    @Test
    public void bothPlanesRequireClosingDoubleRuleAndBothDiamonds() {
        var dir = score(8);
        var paper = source();
        var single = paper.clone();
        paint(single, RIGHT + 10, RIGHT + 14, TOP, BOTTOM, 255);
        var erasure = paper.clone();
        paint(erasure, X - 11, X + 11, UPPER - 31, UPPER - 17, 255);
        assertSame(dir, close(dir, paper, single));
        assertSame(dir, close(dir, paper, erasure));
        assertSame(dir, close(dir, erasure, paper));
    }

    private static ScorePageInterpretation close(ScorePageInterpretation score, byte[] a, byte[] b) {
        return ClosedDoubleHarmonicQuarters.closeTerminalPair(score, a, b, WIDTH, HEIGHT);
    }

    @Test
    public void bothStoppedHeadsCarrySourceOwnedFourthInThePrintedKey() {
        var dir = score(8, -3);
        var paper = source();
        var decoded = close(dir, paper, paper);
        assertEquals(1, decoded.measures().get(BAR).cadenceQuarterBeats());
        for (var note : decoded.notes()) {
            assertEquals(0, note.octaveShift());
            assertEquals(24, note.harmonicSemitones());
        }
        var unprovedInterval = close(score(8, 1), paper, paper);
        assertEquals(1, unprovedInterval.measures().get(BAR).cadenceQuarterBeats());
        for (var note : unprovedInterval.notes()) {
            assertEquals(2, note.octaveShift());
            assertEquals(0, note.harmonicSemitones());
        }
    }

    private static ScorePageInterpretation score(int tie) {
        return score(tie, null);
    }

    private static ScorePageInterpretation score(int tie, Integer key) {
        var bars = List.of(region(25, 66), region(78, 147), region(LEFT, RIGHT));
        var keys = key == null ? List.<ScoreKeyChange>of() : List.of(new ScoreKeyChange(0, key));
        return new ScorePageInterpretation(bars, List.of(head(UPPER, 5, 1, tie), head(LOWER, 0, 2, 0)),
                0, keys, List.of(), List.of(new ScoreMeterChange(0, 2, 4)), List.of());
    }

    private static ScoreNoteEvent head(int y, int step, int beams, int tie) {
        return new ScoreNoteEvent(BAR, (X - LEFT) / (float)(RIGHT - LEFT), step, 0, 1,
                y / (float) HEIGHT, false, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                0, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE)
                .withStemDirection(-1).withOctaveShift(2).withBoundaryTies(tie);
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion(left / (float)WIDTH, right / (float)WIDTH,
                (TOP - 33f) / HEIGHT, (BOTTOM + 34f) / HEIGHT);
    }

    private static byte[] source() {
        var paper = new byte[WIDTH * HEIGHT];
        Arrays.fill(paper, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += GAP) paint(paper, 22, RIGHT + 14, y, y, 0);
        paint(paper, LEFT - 13, LEFT - 10, TOP, BOTTOM, 0);
        paint(paper, RIGHT + 2, RIGHT + 5, TOP, BOTTOM, 0);
        paint(paper, RIGHT + 10, RIGHT + 14, TOP, BOTTOM, 0);
        paint(paper, X - 11, X - 9, UPPER - 27, LOWER + 35, 0);
        for (int center : new int[] {UPPER, LOWER}) {
            for (int y = -9; y <= 9; y++) {
                for (int x = -10; x <= 10; x++) {
                    float radius = Math.abs(x) / 9f + Math.abs(y) / 7f;
                    if (radius <= 1.15 && radius >= .59)
                        paper[(center - 24 + y) * WIDTH + X + x] = 0;
                    if (x * x / 100f + y * y / 26f <= 1)
                        paper[(center + y) * WIDTH + X + x] = 0;
                }
            }
        }
        return paper;
    }

    private static void paint(byte[] paper, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) paper[y * WIDTH + x] = (byte)value;
    }
}
