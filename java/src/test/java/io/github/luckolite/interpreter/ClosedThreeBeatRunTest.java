// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClosedThreeBeatRunTest {
    private static final int WIDTH = 880;
    private static final int HEIGHT = 420;
    private static final int[] HEADS = {327, 365, 401, 445, 482, 528, 570, 606, 644, 681, 718};
    private static final MeasureRegion BEFORE =
            new MeasureRegion(64f / WIDTH, 282f / WIDTH, 78f / HEIGHT, 213f / HEIGHT);
    private static final MeasureRegion END =
            new MeasureRegion(300f / WIDTH, 762f / WIDTH, 78f / HEIGHT, 213f / HEIGHT);
    private static final MeasureRegion FOLLOWING =
            new MeasureRegion(44f / WIDTH, 610f / WIDTH, 248f / HEIGHT, 365f / HEIGHT);
    private static final PlayingTechniqueDetector.Staff STAFF =
            new PlayingTechniqueDetector.Staff(104, 168, 16, 0, 1);

    @Test
    public void closedFourFourThreeRunHasTenSixteenthsAndAClosingEighth() {
        assertTrue(proves(source(), FOLLOWING));
    }

    @Test
    public void anExtraMarkOrLostRailKeepsTheSurroundingMeter() {
        var rest = source();
        paint(rest, 459, 464, 115, 163, 0);
        assertFalse(proves(rest, FOLLOWING));
        var noSecondary = source();
        paint(noSecondary, 646, 662, 133, 140, 255);
        assertFalse(proves(noSecondary, FOLLOWING));
        var noClose = source();
        paint(noClose, 759, 768, 103, 169, 255);
        assertFalse(proves(noClose, FOLLOWING));
    }

    @Test
    public void theSameRhythmInsideAContinuousStaffIsNotAShortBar() {
        var onSameLine =
                new MeasureRegion(781f / WIDTH, 860f / WIDTH, 78f / HEIGHT, 213f / HEIGHT);
        assertFalse(proves(source(), onSameLine));
    }

    @Test
    public void provedThreeBeatCapacitySurvivesTheClockAndMeasureContract() {
        var region = new MeasureRegion(END.left(), END.right(), END.top(), END.bottom(), 3);
        var half = new MeasureRegion(BEFORE.left(), BEFORE.right(), BEFORE.top(), BEFORE.bottom(), 2);
        var measures = List.of(half, region, FOLLOWING);
        var cadences = ScoreBoundaryClock.cadences(measures);
        var clock = ScoreBoundaryClock.legacy().withSourceCadences(cadences);
        assertEquals(java.util.Map.of(0, 2, 1, 3), clock.sourceCadences());
        var score = new ScorePageInterpretation(measures, notes(), 1, List.of(), List.of(), List.of());
        float[] beats = {4, 4, 4};
        var simultaneous = PrintedCadenceDurations.apply(score, beats);
        assertArrayEquals(new float[] {2, 3, 4}, beats, 0);
        assertArrayEquals(new boolean[] {true, false, false}, simultaneous);
        assertThrows(IllegalArgumentException.class,
                () -> new MeasureRegion(END.left(), END.right(), END.top(), END.bottom(), 4));
        assertThrows(IllegalArgumentException.class,
                () -> clock.withSourceCadences(java.util.Map.of(1, 4)));
    }

    private static boolean proves(byte[] gray, MeasureRegion next) {
        return PrintedCadenceDurations.closedThreeBeatRun(
                gray, WIDTH, HEIGHT, BEFORE, END, next, notes(), List.of(STAFF));
    }

    private static List<ScoreNoteEvent> notes() {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < HEADS.length; i++) {
            notes.add(new ScoreNoteEvent(
                    1, (HEADS[i] - 300f) / 462, 9, 0, 1, 96f / HEIGHT, false, 0,
                    i == HEADS.length - 1 ? 1 : 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1,
                    0, 0, ScoreNoteEvent.CLEF_TREBLE, false, 0, false, 0, 0, 1, -1));
        }
        return notes;
    }

    private static byte[] source() {
        var raster = new byte[WIDTH * HEIGHT];
        Arrays.fill(raster, (byte) 255);
        for (int rail = 104; rail <= 168; rail += 16)
            paint(raster, 61, 764, rail, rail, 0);
        paint(raster, 290, 292, 104, 168, 0);
        paint(raster, 762, 764, 104, 168, 0);
        for (int x : HEADS) {
            paint(raster, x - 10, x - 9, 96, 154, 0);
            paint(raster, x - 10, x - 2, 94, 98, 0);
            for (int dy = -6; dy <= 6; dy++) {
                for (int dx = -8; dx <= 8; dx++) {
                    if (dx * dx / 64d + dy * dy / 36d <= 1)
                        raster[(96 + dy) * WIDTH + x + dx] = 0;
                }
            }
        }
        ribbons(raster, 0, 3, 3);
        ribbons(raster, 4, 7, 7);
        ribbons(raster, 8, 10, 9);
        return raster;
    }

    private static void ribbons(byte[] raster, int first, int last, int secondaryLast) {
        paint(raster, HEADS[first] - 10, HEADS[last] - 9, 150, 154, 0);
        paint(raster, HEADS[first] - 10, HEADS[secondaryLast] - 9, 134, 138, 0);
    }

    private static void paint(byte[] gray, int x1, int x2, int y1, int y2, int value) {
        for (int y = y1; y <= y2; y++)
            Arrays.fill(gray, y * WIDTH + x1, y * WIDTH + x2 + 1, (byte) value);
    }
}
