// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedSingleHarmonicQuarterTest {
    private static final int WIDTH = 442, HEIGHT = 432;
    private static final int LEFT = 174, RIGHT = 286, HEAD = 219, FLOOR = 220, GAP = 16;
    private static final int STOPPED = FLOOR + 24, SHAFT = HEAD + 10;

    @Test
    public void quarterAndHollowTouchShareOneArtificialHarmonicAttack() throws Exception {
        var score = score(2, null);
        var ink = paper();
        var revised = ClosedSingleHarmonicQuarter.capture(score, new byte[ink.length], ink, ink, WIDTH, HEIGHT);
        assertEquals(1, revised.measures().get(1).cadenceQuarterBeats());
        assertEquals(List.of(score.notes().get(0).withHarmonicSemitones(24)), revised.notes());
        var identity = Main.noteIdentity(revised.notes().get(0), 2);
        assertEquals(59, identity.get("writtenMidi"));
        assertEquals(64, identity.get("harmonicTouchMidi"));
        assertEquals(83, identity.get("midi"));
    }

    @Test
    public void onlyAnOverlappingLiteralRestCanBeTheDiamondAlias() {
        var ink = paper();
        var alias = rest(HEAD - 4);
        var withAlias = score(0, alias);
        var revised = ClosedSingleHarmonicQuarter.capture(withAlias, new byte[ink.length], ink, ink, WIDTH, HEIGHT);
        assertEquals(1, revised.measures().get(1).cadenceQuarterBeats());
        assertEquals(List.of(), revised.rests());
        assertEquals(withAlias.notes(), revised.notes());
        var independent = score(0, rest(HEAD + 20));
        assertSame(independent,
                ClosedSingleHarmonicQuarter.capture(independent, new byte[ink.length], ink, ink, WIDTH, HEIGHT));
    }

    @Test
    public void bothPlanesNeedTheStemDiamondAndDoubleRule() {
        var ink = paper();
        var score = score(2, null);
        for (int[] cut : new int[][]{
            {HEAD - 12, FLOOR - 11, HEAD + 8, FLOOR + 11},
            {SHAFT - 2, FLOOR + 10, SHAFT + 2, FLOOR + 16},
            {RIGHT, FLOOR - 4 * GAP, RIGHT + 6, FLOOR}
        }) {
            var missing = ink.clone();
            rectangle(missing, cut[0], cut[1], cut[2], cut[3], (byte) 255);
            assertSame(score, ClosedSingleHarmonicQuarter.capture(
                    score, new byte[ink.length], ink, missing, WIDTH, HEIGHT));
            assertSame(score, ClosedSingleHarmonicQuarter.capture(
                    score, new byte[ink.length], missing, ink, WIDTH, HEIGHT));
        }
        var separateWhole = score(4, null);
        assertSame(separateWhole, ClosedSingleHarmonicQuarter.capture(
                separateWhole, new byte[ink.length], ink, ink, WIDTH, HEIGHT));
    }

    private static ScorePageInterpretation score(int touchDuration, ScoreRestEvent rest) {
        var stop = note(STOPPED, -3, 1);
        var notes = touchDuration == 0
                ? List.of(stop.withOctaveShift(2))
                : List.of(stop, note(FLOOR, 0, touchDuration));
        return new ScorePageInterpretation(List.of(region(30,154,122,254), region(LEFT,RIGHT,122,254),
                region(42,264,277,409)), notes, 0,
                List.of(new ScoreKeyChange(0,2)), List.of(), List.of(new ScoreMeterChange(0,4,4)),
                rest == null ? List.of() : List.of(rest));
    }

    private static ScoreNoteEvent note(int y, int step, int duration) {
        return new ScoreNoteEvent(1, (HEAD - LEFT) / (float)(RIGHT - LEFT), step, 0, 1,
                y / (float)HEIGHT, false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                duration, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE).withStemDirection(1);
    }

    private static ScoreRestEvent rest(int x) {
        return new ScoreRestEvent(1, (x-LEFT)/(float)(RIGHT-LEFT), (FLOOR + 2)/(float)HEIGHT,
                22f/HEIGHT, 0, 1, .5);
    }

    private static byte[] paper() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte)255);
        for (int y = FLOOR - GAP * 4; y <= FLOOR; y += GAP)
            rectangle(ink, 23, y, RIGHT + 6, y, (byte)0);
        rectangle(ink, 165, FLOOR - GAP * 4, 166, FLOOR, (byte)0);
        rectangle(ink, RIGHT - 3, FLOOR - GAP * 4, RIGHT - 2, FLOOR, (byte)0);
        rectangle(ink, RIGHT + 4, FLOOR - GAP * 4, RIGHT + 6, FLOOR, (byte)0);
        rectangle(ink, SHAFT - 1, FLOOR - 54, SHAFT + 1, STOPPED, (byte)0);
        for (int y = -10; y <= 10; y++) {
            for (int x = -10; x <= 10; x++) {
                int radius = Math.abs(x) + Math.abs(y);
                if (radius >= 8 && radius <= 11) ink[(FLOOR+y)*WIDTH+HEAD+x] = 0;
                if (x*x/100.0 + y*y/28.0 <= 1) ink[(STOPPED+y)*WIDTH+HEAD+x] = 0;
            }
        }
        return ink;
    }

    private static void rectangle(byte[] drawn, int left, int top, int right, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(drawn, y*WIDTH+left, y*WIDTH+right+1, value);
    }

    private static MeasureRegion region(int l, int r, int t, int b) {
        return new MeasureRegion(l / (float)WIDTH, r / (float)WIDTH, t / (float)HEIGHT, b / (float)HEIGHT);
    }
}
