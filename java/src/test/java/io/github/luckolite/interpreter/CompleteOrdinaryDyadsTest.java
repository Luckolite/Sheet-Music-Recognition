// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class CompleteOrdinaryDyadsTest {
    private static final int WIDTH = 720, HEIGHT = 250;
    private static final int UPPER = 119, LOWER = 159, OUTER = 65, INNER = 78;
    private static final List<MeasureRegion> BARS = List.of(new MeasureRegion(0, 1, .22f, .732f));
    private static final List<ScoreMeterChange> FOUR_FOUR = List.of(new ScoreMeterChange(0, 4, 4));
    private static final String[] THREE = {
        "..#######...", ".##########.", "###......###", "####.....###",
        "####.....###", "####.....###", ".##.....####", ".......####.",
        "......####..", "....#####...", "....#####...", "....#####...",
        "......####..", ".......####.", "##.....####.", "###....####.",
        "###....####.", "###....####.", ".###....###.", "..########..",
        "..########..", "....####...."
    };

    @Test public void aCompleteBinaryBarRejectsTheFingerThree() {
        var input = notes();
        var result = disambiguate(input, image(false, false));
        assertEquals(32, result.size());
        assertTrue(result.stream().allMatch(n -> n.tupletDivisor() == 1));
        for (int i = 0; i < 26; i++) assertSame(input.get(i), result.get(i));
    }

    @Test public void theTrueBracketlessTripletRetainsItsOwnSecondaryBeam() {
        var input = notes();
        assertSame(input, disambiguate(input, image(true, false)));
    }

    @Test public void anExplicitBracketOnTheSameBeamTakesPriority() {
        var input = notes();
        assertSame(input, disambiguate(input, image(false, true)));
    }

    @Test public void aShorterBarAndOtherMeterDoNotSupplyACompleteBinaryClock() {
        var input = notes();
        var shorter = input.subList(2, 32);
        var gray = image(false, false);
        assertSame(shorter, disambiguate(shorter, gray));
        assertSame(input, TripletRhythmDetector.ordinarySixteenthDyadBars(input, List.of(), BARS,
                List.of(new ScoreMeterChange(0, 3, 4)), gray, WIDTH, HEIGHT));
    }

    private static List<ScoreNoteEvent> disambiguate(List<ScoreNoteEvent> notes, byte[] gray) {
        return TripletRhythmDetector.ordinarySixteenthDyadBars(notes, List.of(), BARS, FOUR_FOUR,
                gray, WIDTH, HEIGHT);
    }

    private static int center(int i) { return 54 + i * 33 + i / 4 * 16; }

    private static List<ScoreNoteEvent> notes() {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 16; i++) {
            for (int y : new int[]{UPPER, LOWER}) {
                var head = new ScoreNoteEvent(0, center(i) / (float) WIDTH, y == UPPER ? 5 : 0,
                        0, 1, y / (float) HEIGHT, false, 0, 2, 2, 0, 1).withStemDirection(1);
                notes.add(i < 13 ? head : head.withTupletRatio(3, 2));
            }
        }
        return notes;
    }

    private static byte[] image(boolean shortenedSecondary, boolean bracket) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int i = 0; i < 16; i+=4) {
            paint(gray, center(i)+8, center(i+3)+11, OUTER-2, OUTER+2);
            int first = shortenedSecondary && i == 12 ? 13 : i;
            paint(gray, center(first)+8, center(i+3)+11, INNER-2, INNER+2);
        }
        for (int i = 0; i < 16; i++) {
            int x = center(i);
            paint(gray, x+8, x+10, OUTER, LOWER);
            paint(gray, x-6, x+10, UPPER-4, UPPER+4);
            paint(gray, x-6, x+10, LOWER-4, LOWER+4);
        }
        int numberX = center(14)-5;
        for (int y = 0; y < THREE.length; y++)
            for (int x = 0; x < 12; x++)
                if (THREE[y].charAt(x)=='#') gray[(33+y)*WIDTH+numberX+x] = 0;
        if (bracket) {
            paint(gray, center(13)-3, numberX-2, 42, 43);
            paint(gray, numberX+13, center(15)+3, 42, 43);
        }
        return gray;
    }

    private static void paint(byte[] gray, int x1, int x2, int y1, int y2) {
        for (int y = y1; y <= y2; y++)
            for (int x = x1; x <= x2; x++) gray[y*WIDTH+x] = 0;
    }
}
