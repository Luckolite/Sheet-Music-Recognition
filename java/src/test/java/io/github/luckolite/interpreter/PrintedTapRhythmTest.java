// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class PrintedTapRhythmTest {
    private static final int WIDTH = 1400;
    private static final int HEIGHT = 900;
    private static final int TOP = 215;
    private static final int GAP = 16;
    private static final int LEFT = 180;
    private static final int RIGHT = 860;
    private static final List<MeasureRegion> MEASURES = List.of(
            new MeasureRegion((float) LEFT / WIDTH, (float) RIGHT / WIDTH, .12f, .46f),
            new MeasureRegion((float) (RIGHT + 18) / WIDTH, .96f, .12f, .46f));
    private static final List<PlayingTechniqueDetector.Staff> STAFFS = List.of(
            new PlayingTechniqueDetector.Staff(120, 168, 12, 0, 3),
            new PlayingTechniqueDetector.Staff(TOP, TOP + 4 * GAP, GAP, 1, 3),
            new PlayingTechniqueDetector.Staff(340, 404, GAP, 2, 3));
    private static final float LABEL_TOP = 302f / HEIGHT;
    private static final float LABEL_BOTTOM = 320f / HEIGHT;
    private static final PlayingTechniqueDetector.Word LABEL =
            new PlayingTechniqueDetector.Word("Tap rhythm", (float) LEFT / WIDTH, LABEL_TOP,
                    (LEFT + 95f) / WIDTH, LABEL_BOTTOM);

    @Test
    public void taughtDiagonalsRecoverCompleteTypedRhythmAndPreserveOtherParts() {
        var violin = note(0, 0);
        var pianoLater = note(1, 1);
        var notes = List.of(violin, note(0, 1), pianoLater);
        var result = recover(notes, List.of(LABEL), 12, raster(true));
        var taps = result.stream().filter(n -> n.kind() == ScoreNoteEvent.Kind.UNPITCHED).toList();
        assertEquals(15, taps.size());
        assertEquals(9, taps.stream().filter(n -> n.beamCount() == 1).count());
        assertEquals(6, taps.stream().filter(n -> n.beamCount() == 2).count());
        assertEquals(List.of(violin, pianoLater), result.stream()
                .filter(n -> n.kind() == ScoreNoteEvent.Kind.PITCHED).toList());
        assertEquals(1, taps.get(0).staffIndex());
        assertEquals(0, taps.get(0).measureIndex());
    }

    @Test
    public void rejectsInferredInkMissingCaptionAndIncompleteMeter() {
        var notes = List.of(note(0, 0), note(0, 1));
        var gray = raster(true);
        assertSame(notes, recover(notes, List.of(), 12, gray));
        assertSame(notes, recover(notes, List.of(LABEL), 10, gray));
        assertSame(notes, recover(notes, List.of(LABEL), 12, raster(false)));
    }

    private static List<ScoreNoteEvent> recover(List<ScoreNoteEvent> notes,
            List<PlayingTechniqueDetector.Word> words, int meter, byte[] gray) {
        return PrintedTapRhythm.recover(notes, words, STAFFS, MEASURES,
                List.of(new ScoreMeterChange(0, meter, 8)), gray, WIDTH, HEIGHT);
    }

    private static ScoreNoteEvent note(int measure, int staff) {
        return new ScoreNoteEvent(measure, .15f, 4, staff, 3, .27f, false,
                0, 1, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0,
                ScoreNoteEvent.CLEF_TREBLE, false, 0, false, 0, 0, 1, 1);
    }

    private static byte[] raster(boolean diagonal) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int staffY = TOP; staffY <= TOP + 4 * GAP; staffY += GAP)
            ink(gray, LEFT, RIGHT, staffY, staffY);
        int[] groups = {3, 3, 6, 3};
        int x = LEFT + 38;
        for (int group : groups) {
            int first = x;
            for (int note = 0; note < group; note++) {
                ink(gray, x - 1, x + 1, TOP - 20, TOP + 22);
                for (int y = TOP + 17; y < TOP + 46; y++) {
                    int center = diagonal ? x - Math.max(0, Math.round((y - TOP - 17) * 1.08f)) : x;
                    ink(gray, center - 4, center + 4, y, y);
                }
                x += group == 6 ? 36 : 46;
            }
            int last = x - (group == 6 ? 36 : 46);
            ink(gray, first, last, TOP - 20, TOP - 12);
            if (group == 6) ink(gray, first, last, TOP - 8, TOP - 5);
        }
        for (int y = 302; y < 319; y++)
            for (int at = LEFT; at < LEFT + 95; at++)
                if (at % 7 < 3 && y % 4 < 3) gray[y * WIDTH + at] = 0;
        return gray;
    }

    private static void ink(byte[] gray, int first, int last, int upper, int lower) {
        for (int y = upper; y <= lower; y++)
            Arrays.fill(gray, y * WIDTH + first, y * WIDTH + last + 1, (byte) 0);
    }
}
