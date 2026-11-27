// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DetachedDottedSixteenthRestTest {
    private static final int WIDTH = 476, HEIGHT = 160, GAP = 14;
    private static final List<MeasureRegion> BARS = List.of(new MeasureRegion(0, 1, 0, 1));
    private static final List<SixteenthRestDetector.Staff> STAFFS =
            List.of(new SixteenthRestDetector.Staff(36, 92, GAP, 0, 1));
    private static final List<ScoreNoteEvent> NOTES =
            List.of(note(42, 1), note(152, 1), note(262, 0), note(318, 0), note(374, 0), note(430, 0));
    private static final SixteenthRestDetector.Detection EMPTY =
            new SixteenthRestDetector.Detection(List.of(), List.of());

    @Test
    public void twoOpticalBodiesCompleteTheDottedPairAndFourEighths() {
        var ink = white();
        body(ink, 109, 81);
        body(ink, 219, 58);
        var rests = recover(ink, EMPTY, NOTES).rests();
        assertEquals(2, rests.size());
        assertEquals(.25, rests.get(0).durationBeats(), 0);
        assertEquals(.25, rests.get(1).durationBeats(), 0);
        assertEquals((109 - 2.5) / WIDTH, rests.get(0).positionInMeasure(), .01);
        assertEquals((219 - 2.5) / WIDTH, rests.get(1).positionInMeasure(), .01);
    }

    @Test
    public void emptySecondSlotAndWrongNoteValuesAreRefused() {
        var ink = white();
        body(ink, 109, 81);
        assertTrue(recover(ink, EMPTY, NOTES).rests().isEmpty());
        body(ink, 219, 58);
        var otherRhythm = List.of(note(42, 0), note(152, 1), note(262, 0),
                note(318, 0), note(374, 0), note(430, 0));
        assertTrue(recover(ink, EMPTY, otherRhythm).rests().isEmpty());
    }

    @Test
    public void continuousSecondBeamDoesNotProveSeparateGroups() {
        var ink = white();
        body(ink, 109, 81);
        body(ink, 219, 58);
        for (int y = 48; y <= 51; y++)
            Arrays.fill(ink, y * WIDTH + 158, y * WIDTH + 271, (byte) 0);
        assertTrue(recover(ink, EMPTY, NOTES).rests().isEmpty());
    }

    @Test
    public void existingLiteralRestKeepsItsOwnInkPosition() {
        var ink = white();
        body(ink, 109, 81);
        body(ink, 219, 58);
        var existing = new ScoreRestEvent(0, .23f, .55f, .2f, 0, 1, .25);
        var kept = new SixteenthRestDetector.Detection(List.of(existing), List.of());
        var rests = recover(ink, kept, NOTES).rests();
        assertEquals(2, rests.size());
        assertEquals(existing, rests.get(0));
    }

    private static byte[] white() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        drawBeam(ink, 42, 152);
        drawBeam(ink, 262, 318, 374, 430);
        return ink;
    }
    private static void drawBeam(byte[] ink, int... xs) {
        for (int y = 48; y <= 51; y++)
            Arrays.fill(ink, y * WIDTH + xs[0] + 6, y * WIDTH + xs[xs.length - 1] + 9, (byte) 0);
        for (int x : xs) {
            for (int y = 50; y <= 92; y++)
                Arrays.fill(ink, y * WIDTH + x + 6, y * WIDTH + x + 9, (byte) 0);
            for (int dy = -4; dy <= 4; dy++)
                for (int dx = -6; dx <= 6; dx++)
                    if (dx * dx / 36d + dy * dy / 16d <= 1)
                        ink[(92 + dy) * WIDTH + x + dx] = 0;
        }
    }

    private static void body(byte[] ink, int x, int y) {
        for (int bulb = 0; bulb < 2; bulb++) {
            int centerX = x - bulb * 5, centerY = y + bulb * 13;
            for (int dy = -4; dy <= 4; dy++)
                for (int dx = -3; dx <= 3; dx++)
                    if (dx * dx / 9d + dy * dy / 16d <= 1)
                        ink[(centerY + dy) * WIDTH + centerX + dx] = 0;
        }
        for (int shift = 2; shift <= 33; shift++) {
            int centerX = x + 10 - Math.round(shift * .42f);
            int offset = (y + shift) * WIDTH + centerX;
            Arrays.fill(ink, offset - 1, offset + 2, (byte) 0);
        }
    }
    private static ScoreNoteEvent note(int x, int dots) {
        return new ScoreNoteEvent(0, x / (float) WIDTH, 0, 0, 1, 92f / HEIGHT, false,
                dots, 1, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0,
                ScoreNoteEvent.CLEF_TREBLE).withStemDirection(1);
    }
    private static SixteenthRestDetector.Detection recover(byte[] ink,
            SixteenthRestDetector.Detection initial, List<ScoreNoteEvent> notes) {
        return RawBeamedTwoBulbRests.addDottedNoteRests(initial, ink, WIDTH, HEIGHT, BARS, STAFFS, notes);
    }
}
