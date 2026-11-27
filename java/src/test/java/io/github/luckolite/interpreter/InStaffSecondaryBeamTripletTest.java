// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

/** Original generated engraving: three double-beamed attacks followed by one eighth. */
public class InStaffSecondaryBeamTripletTest {
    private static final int W = 400, H = 240;
    private static final List<MeasureRegion> BARS =
            List.of(new MeasureRegion(0, 1, .25f, .7166667f));
    private static final String[] THREE = {
        "..#######...", ".##########.", "###......###", "####.....###",
        "####.....###", "####.....###", ".##.....####", ".......####.",
        "......####..", "....#####...", "....#####...", "....#####...",
        "......####..", ".......####.", "##.....####.", "###....####.",
        "###....####.", "###....####.", ".###....###.", "..########..",
        "..########..", "....####...."
    };

    private static byte[] ink(boolean numeral, boolean secondary) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int row : new int[] {110, 124, 138, 152, 166})
            for (int x = 20; x < 380; x++) gray[row * W + x] = 0;
        for (int x = 94; x <= 169; x++) for (int y = 112; y <= 115; y++) gray[y * W + x] = 0;
        if (secondary)
            for (int x = 94; x <= 144; x++) for (int y = 104; y <= 107; y++) gray[y * W + x] = 0;
        for (int x : new int[] {94, 119, 144, 169})
            for (int y = 72; y <= 115; y++) gray[y * W + x] = 0;
        if (numeral)
            for (int y = 0; y < THREE.length; y++)
                for (int x = 0; x < 12; x++)
                    if (THREE[y].charAt(x) == '#') gray[(143 + y) * W + 119 + x] = 0;
        return gray;
    }

    private static List<ScoreNoteEvent> notes() {
        return notes(2);
    }

    private static List<ScoreNoteEvent> notes(int beams) {
        List<ScoreNoteEvent> result = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            result.add(
                    new ScoreNoteEvent(
                            0,
                            .25f + i * .0625f,
                            i == 1 ? 12 : 11,
                            0,
                            1,
                            (i == 1 ? 72f : 79f) / H,
                            false,
                            0,
                            i < 3 ? beams : beams - 1,
                            2,
                            0));
        return result;
    }

    @Test
    public void physicalSecondaryBeamOwnsPrintedThreeInsideStaff() {
        var result = TripletRhythmDetector.apply(notes(), BARS, ink(true, true), W, H);
        for (int i = 0; i < 3; i++) assertEquals(3, result.get(i).tupletDivisor());
        assertEquals(1, result.get(3).tupletDivisor());
    }

    @Test
    public void secondaryBeamWithoutPrintedNumeralDoesNotInventTuplet() {
        var original = notes();
        assertEquals(original, TripletRhythmDetector.apply(original, BARS, ink(false, true), W, H));
    }

    @Test
    public void printedThreeWithoutPhysicalSecondaryBeamCannotGroup() {
        var original = notes();
        assertEquals(original, TripletRhythmDetector.apply(original, BARS, ink(true, false), W, H));
    }

    @Test
    public void separateSecondaryBeamsCannotSupplyOneGroup() {
        byte[] gray = ink(true, true);
        for (int x = 121; x < 134; x++)
            for (int y = 104; y <= 107; y++) gray[y * W + x] = (byte) 255;
        var original = notes();
        assertEquals(original, TripletRhythmDetector.apply(original, BARS, gray, W, H));
    }
    @Test
    public void boundedThirdRailKeepsThirtySecondTripletWithinLongerBeam() {
        byte[] gray = ink(true, true);
        for (int x = 94; x <= 169; x++)
            for (int y = 120; y <= 123; y++) gray[y * W + x] = 0;
        for (int x : new int[] {94, 119, 144, 169})
            for (int y = 116; y <= 123; y++) gray[y * W + x] = 0;

        var original = notes(3);
        var result = TripletRhythmDetector.apply(original, BARS, gray, W, H);
        for (int i = 0; i < 3; i++) {
            assertEquals(3, result.get(i).tupletDivisor());
            assertEquals(1. / 12, ScoreNoteTiming.writtenDurationBeats(result.get(i)), 1e-8);
        }
        assertEquals(original.get(3), result.get(3));
        for (int y = 143; y < 143 + THREE.length; y++)
            for (int x = 119; x < 131; x++)
                if (THREE[y - 143].charAt(x - 119) == '#') gray[y * W + x] = (byte) 255;
        assertEquals(original, TripletRhythmDetector.apply(original, BARS, gray, W, H));
    }

    @Test
    public void printedTripletCanRejectOnlyWeakArtworkRestAboveStaff() {
        var bars = List.of(new MeasureRegion(0, 1, .40f, .80f));
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int x : new int[] {100, 125, 150}) {
            notes.add(new ScoreNoteEvent(0, x / (float) W, 5, 0, 1, 110f / H,
                    false, 0, 1, 2, 0).withStemDirection(-1));
            for (int y = 110; y <= 144; y++)
                for (int xx = x - 6; xx <= x - 4; xx++) gray[y * W + xx] = 0;
        }
        for (int y = 141; y <= 144; y++)
            Arrays.fill(gray, y * W + 94, y * W + 147, (byte) 0);
        for (int y = 0; y < THREE.length; y++)
            for (int x = 0; x < THREE[y].length(); x++)
                if (THREE[y].charAt(x) == '#') gray[(156 + y) * W + 119 + x] = 0;
        var rest = new ScoreRestEvent(0, .34f, 62f / H, 24f / H, 0, 1, 1);
        for (int y = 54; y <= 70; y++) gray[y * W + 136 + y / 3 - 20] = (byte) 148;

        byte[] normalized = gray.clone();
        for (int y = 54; y <= 70; y++) normalized[y * W + 136 + y / 3 - 20] = 0;
        var result = TripletRhythmDetector.withRests(notes, List.of(rest), bars, normalized, gray, W, H);
        assertTrue(result.rests().isEmpty());
        for (var n : result.notes()) assertEquals(3, n.tupletDivisor());
        byte[] noNumeral = gray.clone();
        for (int y = 156; y < 156 + THREE.length; y++)
            Arrays.fill(noNumeral, y * W + 119, y * W + 119 + THREE[0].length(), (byte) 255);
        assertEquals(List.of(rest),
                TripletRhythmDetector.withRests(notes, List.of(rest), bars, noNumeral, W, H).rests());

        for (int y = 54; y <= 70; y++) gray[y * W + 136 + y / 3 - 20] = 0;
        assertEquals(List.of(rest),
                TripletRhythmDetector.withRests(notes, List.of(rest), bars, gray, W, H).rests());
    }

    @Test
    public void quarterRestBoxAroundOwnedSixteenthTripletNumeralIsDiscarded() {
        var bars = List.of(new MeasureRegion(0, 1, 108f / H, 236f / H));
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y = 132; y <= 196; y += 16)
            Arrays.fill(gray, y * W + 20, y * W + 370, (byte) 0);
        var notes = new ArrayList<ScoreNoteEvent>();
        int[] attacks = {100, 132, 164};
        for (int x : attacks) {
            notes.add(new ScoreNoteEvent(0, x / (float) W, 0, 0, 1, 196f / H,
                    false, 0, 2, 2, 0).withStemDirection(1));
            for (int y = 147; y <= 196; y++)
                for (int col = x + 5; col <= x + 7; col++) gray[y * W + col] = 0;
        }
        for (int y : new int[] {147, 148, 149, 155, 156, 157})
            Arrays.fill(gray, y * W + 105, y * W + 172, (byte) 0);
        var rest = new ScoreRestEvent(0, 146f / W, 99f / H, 48f / H, 0, 1, 1);
        var distinct = new ScoreRestEvent(0, 146f / W, 112f / H, 48f / H, 0, 1, 1);
        notes.set(1, notes.get(1).withFollowingRest(1));

        for (int y = 0; y < THREE.length; y++)
            for (int x = 0; x < THREE[y].length(); x++)
                if (THREE[y].charAt(x) == '#') gray[(101 + y) * W + 138 + x] = 0;
        var result = TripletRhythmDetector.withRests(notes, List.of(rest), bars, gray, W, H);
        assertTrue(result.rests().isEmpty());
        for (var note : result.notes()) {
            assertEquals(1.0 / 6, ScoreNoteTiming.writtenDurationBeats(note), 1e-8);
            assertEquals(0, note.followingRestBeats(), 1e-8);
        }
        assertEquals(List.of(distinct),
                TripletRhythmDetector.withRests(notes, List.of(distinct), bars, gray, W, H).rests());

        for (int y = 101; y < 101 + THREE.length; y++)
            Arrays.fill(gray, y * W + 138, y * W + 138 + THREE[0].length(), (byte) 255);
        assertEquals(List.of(rest),
                TripletRhythmDetector.withRests(notes, List.of(rest), bars, gray, W, H).rests());
    }

    @Test
    public void finalSourceRetryNeedsTheTwoHooksOnAMixedTriplet() {
        var bars = List.of(new MeasureRegion(0, 1, .4f, .8f));
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        var notes = List.of(
                new ScoreNoteEvent(0, .25f, 5, 0, 1, 110f / H, false, 0, 0, 2, 1)
                        .withStemDirection(-1),
                new ScoreNoteEvent(0, .375f, 4, 0, 1, 116f / H, false, 0, 1, 2, 0)
                        .withStemDirection(-1));
        for (int y = 0; y < THREE.length; y++)
            for (int x = 0; x < THREE[y].length(); x++)
                if (THREE[y].charAt(x) == '#') gray[(156 + y) * W + 118 + x] = 0;
        gray[156 * W + 127] = (byte) 145;
        for (int x = 97; x <= 115; x++) gray[170 * W + x] = 0;
        for (int x = 132; x <= 155; x++) gray[170 * W + x] = 0;
        for (int y = 158; y <= 170; y++) gray[y * W + 97] = gray[y * W + 155] = 0;
        var score = new ScorePageInterpretation(bars, notes);
        var corrected = TripletRhythmDetector.recoverSourceTriplets(score, gray, W, H);
        assertEquals(3, corrected.notes().get(0).tupletDivisor());
        assertEquals(3, corrected.notes().get(1).tupletDivisor());
        assertEquals(1, corrected.notes().stream().mapToDouble(ScoreNoteTiming::writtenDurationBeats).sum(), 1e-8);

        var ornament = new ScoreNoteEvent(0, .31f, 6, 0, 1, 102f / H,
                false, 0, 1, 2, 0).withStemDirection(1);
        var grace = ornament.withArticulations(NoteOrnament.GRACE);
        var decorated = new ScorePageInterpretation(bars, List.of(notes.get(0), grace, notes.get(1)));
        var withGrace = TripletRhythmDetector.recoverSourceTriplets(decorated, gray, W, H);
        assertEquals(corrected.notes().get(0), withGrace.notes().get(0));
        assertEquals(grace, withGrace.notes().get(1));
        assertEquals(corrected.notes().get(1), withGrace.notes().get(2));
        var metrical = new ScorePageInterpretation(bars, List.of(notes.get(0), ornament, notes.get(1)));
        assertSame(metrical, TripletRhythmDetector.recoverSourceTriplets(metrical, gray, W, H));

        for (int y = 158; y < 170; y++) gray[y * W + 155] = (byte) 255;
        assertSame(score, TripletRhythmDetector.recoverSourceTriplets(score, gray, W, H));
    }

}
