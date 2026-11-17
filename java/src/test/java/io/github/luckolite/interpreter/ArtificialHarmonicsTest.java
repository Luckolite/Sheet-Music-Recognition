// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original raster drawings, not extracted score material. */
public class ArtificialHarmonicsTest {
    private static final int W = 240, H = 240, X = 100, Y = 160, G = 20;
    private final byte[] gray = new byte[W * H];

    public ArtificialHarmonicsTest() {
        Arrays.fill(gray, (byte) 255);
    }

    private void shape(boolean diamond) {
        for (int y = Y - 45; y <= Y - 15; y++)
            for (int x = X - 15; x <= X + 15; x++) {
                double r =
                        diamond
                                ? (Math.abs(x - X) + Math.abs(y - (Y - 30))) / 12d
                                : Math.sqrt(
                                        Math.pow((x - X) / 12d, 2)
                                                + Math.pow((y - (Y - 30)) / 7d, 2));
                if (r >= .65 && r <= 1.1) gray[y * W + x] = 0;
            }
        for (int y = Y - 7; y <= Y + 7; y++)
            for (int x = X - 11; x <= X + 11; x++)
                if (Math.pow((x - X) / 11d, 2) + Math.pow((y - Y) / 7d, 2) <= 1)
                    gray[y * W + x] = 0;
        for (int y = Y - 42; y <= Y; y++) for (int x = X + 9; x <= X + 11; x++) gray[y * W + x] = 0;
    }

    private ScoreNoteEvent note(int step, float y) {
        return new ScoreNoteEvent(
                0,
                X / (float) W,
                step,
                0,
                1,
                y / H,
                false,
                0,
                1,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                0,
                0,
                0,
                0,
                ScoreNoteEvent.CLEF_TREBLE);
    }

    private ScoreNoteEvent heldUpper() {
        return new ScoreNoteEvent(
                0,
                X / (float) W,
                3,
                0,
                1,
                (Y - 30) / (float) H,
                false,
                0,
                0,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                ScoreNoteEvent.DURATION_HALF,
                0,
                0,
                0,
                ScoreNoteEvent.CLEF_TREBLE);
    }

    private ScoreNoteEvent beamedStopped() {
        return new ScoreNoteEvent(
                0,
                X / (float) W,
                0,
                0,
                1,
                Y / (float) H,
                false,
                0,
                2,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                0,
                0,
                0,
                0,
                ScoreNoteEvent.CLEF_TREBLE);
    }

    private ScoreNoteEvent beamedUpper() {
        return new ScoreNoteEvent(
                0,
                X / (float) W,
                3,
                0,
                1,
                (Y - 30) / (float) H,
                false,
                0,
                2,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                0,
                0,
                0,
                0,
                ScoreNoteEvent.CLEF_TREBLE);
    }

    private List<ScoreNoteEvent> apply(List<ScoreNoteEvent> notes) {
        return ArtificialHarmonics.apply(
                gray,
                W,
                H,
                List.of(new MeasureRegion(0, 1, 0, 1)),
                notes,
                List.of(new PlayingTechniqueDetector.Staff(80, 160, G, 0, 1)));
    }

    @Test
    public void fourthDiamondSoundsTwoOctavesAboveStoppedNote() {
        shape(true);
        var n = note(0, Y);
        var result = apply(List.of(n));
        assertEquals(2, result.get(0).octaveShift());
        assertEquals(n.beamCount(), result.get(0).beamCount());
    }

    @Test
    public void recognizedTouchHeadDoesNotBecomeASecondAttack() {
        shape(true);
        var result = apply(List.of(note(0, Y), note(3, Y - 30)));
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).octaveShift());
    }

    private void downStem(boolean diamond) {
        shape(diamond);
        for (int y = Y - 24; y <= Y - 8; y++)
            for (int x = X + 6; x <= X + 19; x++) gray[y * W + x] = (byte) 255;
        for (int y = Y - 30; y <= Y + 32; y++)
            for (int x = X - 11; x <= X - 9; x++) gray[y * W + x] = 0;
    }

    @Test
    public void downStemFourthSoundsTwoOctavesAboveStoppedNote() {
        downStem(true);
        var result = apply(List.of(note(0, Y), note(3, Y - 30)));
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).octaveShift());
    }

    @Test
    public void downStemOrdinaryHollowChordIsRetained() {
        downStem(false);
        var notes = List.of(note(0, Y), note(3, Y - 30));
        assertEquals(notes, apply(notes));
    }

    @Test
    public void interruptedDownStemDoesNotProveHarmonic() {
        downStem(true);
        for (int y = Y + 8; y <= Y + 20; y++)
            for (int x = X - 19; x <= X - 6; x++) gray[y * W + x] = (byte) 255;
        var notes = List.of(note(0, Y), note(3, Y - 30));
        assertEquals(notes, apply(notes));
    }

    @Test
    public void detachedFingeringAboveDownStemDoesNotTranspose() {
        downStem(true);
        for (int y = Y - 20; y <= Y - 8; y++)
            for (int x = X - 19; x <= X - 6; x++) gray[y * W + x] = (byte) 255;
        var notes = List.of(note(0, Y));
        assertEquals(notes, apply(notes));
    }

    @Test
    public void hollowStoppedFourthNeedsStrongerEvidence() {
        downStem(true);
        var hollow =
                new ScoreNoteEvent(
                        0,
                        X / (float) W,
                        0,
                        0,
                        1,
                        Y / (float) H,
                        false,
                        0,
                        0,
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                        ScoreNoteEvent.DURATION_HALF,
                        0,
                        0,
                        0,
                        ScoreNoteEvent.CLEF_TREBLE);
        var notes = List.of(hollow, note(3, Y - 30));
        assertEquals(notes, apply(notes));
    }

    @Test
    public void ordinaryHollowChordHeadIsNotAHarmonic() {
        shape(false);
        var notes = List.of(note(0, Y), note(3, Y - 30));
        assertEquals(notes, apply(notes));
    }

    @Test
    public void detachedDiamondIsInsufficient() {
        shape(true);
        for (int y = Y - 24; y <= Y - 4; y++)
            for (int x = X + 6; x <= X + 19; x++) gray[y * W + x] = (byte) 255;
        var notes = List.of(note(0, Y));
        assertEquals(notes, apply(notes));
    }

    @Test
    public void unrelatedChordToneIsRetained() {
        shape(true);
        var other = note(-4, Y + 40);
        assertTrue(apply(List.of(note(0, Y), other)).contains(other));
    }

    @Test
    public void staffLineCanCrossHollowDiamond() {
        shape(true);
        for (int x = 20; x < 220; x++) gray[(Y - 30) * W + x] = 0;
        assertEquals(2, apply(List.of(note(0, Y))).get(0).octaveShift());
    }

    @Test
    public void crowdedDiamondWithHeldUpperIsRecovered() {
        shape(true);
        for (int rule : new int[] {Y - 48, Y - 40})
            for (int dy = 0; dy < 4; dy++)
                for (int x = 20; x < 220; x++) gray[(rule + dy) * W + x] = 0;
        var stopped = beamedStopped();
        var upper = heldUpper();
        assertFalse(ArtificialHarmonics.diamond(gray, W, H, X, Y - 30, G));
        var result = apply(List.of(stopped, upper));
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).octaveShift());
    }

    @Test
    public void crowdedOrdinaryHollowFourthRemainsTwoNotes() {
        shape(false);
        for (int rule : new int[] {Y - 48, Y - 40})
            for (int dy = 0; dy < 4; dy++)
                for (int x = 20; x < 220; x++) gray[(rule + dy) * W + x] = 0;
        var notes = List.of(beamedStopped(), heldUpper());
        assertEquals(notes, apply(notes));
    }

    @Test
    public void crowdedBeamedOrdinaryFourthRemainsTwoNotes() {
        shape(false);
        for (int rule : new int[] {Y - 48, Y - 40})
            for (int dy = 0; dy < 4; dy++)
                for (int x = 20; x < 220; x++) gray[(rule + dy) * W + x] = 0;
        var notes = List.of(beamedStopped(), beamedUpper());
        assertEquals(notes, apply(notes));
    }

    @Test
    public void crowdedBeamedTouchIsRemoved() {
        shape(true);
        for (int rule : new int[] {Y - 48, Y - 40})
            for (int dy = 0; dy < 4; dy++)
                for (int x = 20; x < 220; x++) gray[(rule + dy) * W + x] = 0;
        var result = apply(List.of(beamedStopped(), beamedUpper()));
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).octaveShift());
    }

    @Test
    public void crowdedCoreWithAStaffRuleStillFormsDiamond() {
        shape(true);
        for (int rule : new int[] {Y - 48, Y - 40})
            for (int dy = 0; dy < 4; dy++)
                for (int x = 20; x < 220; x++) gray[(rule + dy) * W + x] = 0;
        for (int y = Y - 31; y <= Y - 29; y++)
            for (int x = X - 2; x <= X + 2; x++) gray[y * W + x] = 0;
        var result = apply(List.of(beamedStopped(), beamedUpper()));
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).octaveShift());
    }

    @Test
    public void pairedBeamCanWitnessAnInterruptedRawStem() {
        shape(true);
        for (int rule : new int[] {Y - 48, Y - 40})
            for (int dy = 0; dy < 4; dy++)
                for (int x = 20; x < 220; x++) gray[(rule + dy) * W + x] = 0;
        for (int y = Y - 10; y <= Y - 3; y++)
            for (int x = X + 6; x <= X + 19; x++) gray[y * W + x] = (byte) 255;
        var result = apply(List.of(beamedStopped(), heldUpper()));
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).octaveShift());
    }

    @Test
    public void ledgerDiamondRecordedAsAWholeHeadUsesTheStoppedSixteenthShaft() {
        final int ty = Y - 30;
        for (int y = ty - 14; y <= ty + 14; y++)
            for (int x = X - 14; x <= X + 14; x++) {
                double r = Math.abs(x - X) / 12d + Math.abs(y - ty + 2) / 10d;
                if (r >= .25 && r <= 1) gray[y * W + x] = 0;
            }
        for (int y = Y - 7; y <= Y + 7; y++)
            for (int x = X - 11; x <= X + 11; x++)
                if (Math.pow((x - X) / 11d, 2) + Math.pow((y - Y) / 7d, 2) <= 1)
                    gray[y * W + x] = 0;
        for (int y = 65; y <= Y; y++)
            Arrays.fill(gray, y * W + X + 11, y * W + X + 14, (byte) 0);
        for (int y : new int[] {65, 73, ty + 7, ty + 8})
            Arrays.fill(gray, y * W + X - 24, y * W + X + 26, (byte) 0);
        var stopped = beamedStopped().withStemDirection(1);
        var whole = new ScoreNoteEvent(0, X / (float) W, 3, 0, 1, ty / (float) H,
                false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, ScoreNoteEvent.DURATION_WHOLE)
                .withClef(ScoreNoteEvent.CLEF_TREBLE);
        var notes = List.of(stopped, whole);
        var bar = List.of(new MeasureRegion(0, 1, 0, 1));
        var staffs = List.of(new PlayingTechniqueDetector.Staff(80, 160, G, 0, 1));
        assertEquals(notes, apply(notes));
        assertEquals(List.of(stopped.withOctaveShift(2)),
                ArtificialHarmonics.apply(null, gray, gray.clone(), W, H, bar, notes, staffs));

        var filled = gray.clone();
        for (int y = ty - 6; y <= ty + 6; y++)
            Arrays.fill(filled, y * W + X - 6, y * W + X + 7, (byte) 0);
        assertEquals(notes, ArtificialHarmonics.apply(null, gray, filled, W, H, bar, notes, staffs));
        var cutShaft = gray.clone();
        for (int y = ty + 16; y <= ty + 22; y++)
            Arrays.fill(cutShaft, y * W + X + 8, y * W + X + 17, (byte) 255);
        assertEquals(notes, ArtificialHarmonics.apply(null, gray, cutShaft, W, H, bar, notes, staffs));
    }

    @Test
    public void aProvenThirdHookOccupiesTheCounterOnlyWithASolidInterior() throws Exception {
        int center = Y - 30, shaft = X + 10, tip = center - 43;
        for (int row = center - 2; row <= center + 2; row++)
            for (int col = X - 3; col <= shaft; col++) gray[row * W + col] = 0;
        assertTrue(beamOccupiesCounter(center, shaft, tip));
        assertFalse(beamOccupiesCounter(center, shaft, tip - 16));
        for (int row : new int[] {center - 2, center - 1, center + 2})
            for (int col = X - 3; col <= X + 3; col++) gray[row * W + col] = (byte) 255;
        assertFalse(beamOccupiesCounter(center, shaft, tip));
    }

    private boolean beamOccupiesCounter(int y, int shaft, int tip) throws ReflectiveOperationException {
        var judge = ArtificialHarmonics.class.getDeclaredMethod("beamOccupiesTouchCounter",
                byte[].class, int.class, int.class, int.class, int.class, float.class, int.class, int.class);
        judge.setAccessible(true);
        return (boolean) judge.invoke(null, gray, W, H, X, y, G, shaft, tip);
    }

    @Test
    public void missingRawImageDoesNotChangeNotes() {
        var notes = List.of(note(0, Y));
        assertEquals(notes, ArtificialHarmonics.apply(null, W, H, List.of(), notes, List.of()));
    }

    @Test
    public void existingOctaveShiftIsNotStacked() {
        shape(true);
        var notes = List.of(note(0, Y).withOctaveShift(1));
        assertEquals(notes, apply(notes));
    }

    @Test
    public void pixelsAreUnchanged() {
        shape(true);
        byte[] original = gray.clone();
        apply(List.of(note(0, Y)));
        assertArrayEquals(original, gray);
    }
}
