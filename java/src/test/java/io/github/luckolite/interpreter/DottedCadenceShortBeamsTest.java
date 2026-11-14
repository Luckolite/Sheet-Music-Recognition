// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class DottedCadenceShortBeamsTest {
    private static final int WIDTH = 880;
    private static final int HEIGHT = 360;
    private static final int GAP = 16;
    private static final int HEAD_Y = 99;
    private static final int TIP = 173;

    private record Fixture(byte[] gray, List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures, List<PlayingTechniqueDetector.Staff> staffs) {}

    @Test
    public void placesLastTwoAttacksBeforeCadenceUsingOwnedShortBeams() {
        var fixture = fixture(true);
        var notes = apply(fixture);
        assertEquals(13, notes.size());
        for (int i = 0; i < 12; i++) {
            assertEquals(i % 2 == 0 ? 2 : 3, notes.get(i).beamCount());
        }
        assertSame(fixture.notes.get(12), notes.get(12));
        assertEquals(2.5, ScoreNoteTiming.writtenPlacement(notes.get(10), notes, 3)
                .orElseThrow().onsetBeats(), 1e-9);
        assertEquals(2.875, ScoreNoteTiming.writtenPlacement(notes.get(11), notes, 3)
                .orElseThrow().onsetBeats(), 1e-9);
    }

    @Test
    public void requiresAllSixShortBeamsToMeetTheirOwnShafts() {
        var fixture = fixture(false);
        assertSame(fixture.notes, apply(fixture));
    }

    private static List<ScoreNoteEvent> apply(Fixture fixture) {
        return DottedCadenceShortBeams.apply(fixture.notes, fixture.measures, fixture.staffs,
                fixture.gray, WIDTH, HEIGHT);
    }

    private static Fixture fixture(boolean attachFirstHook) {
        var measures = List.of(
                region(94, 685, 33, 165),
                region(703, 791, 33, 165),
                region(90, 415, 220, 352));
        var staffs = List.of(new PlayingTechniqueDetector.Staff(67, 131, GAP, 0, 1),
                new PlayingTechniqueDetector.Staff(254, 318, GAP, 0, 1));
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 67; y <= 131; y += GAP) rectangle(gray, 85, y, 795, y);
        rectangle(gray, 692, 67, 695, 131);
        rectangle(gray, 787, 67, 789, 131);
        rectangle(gray, 793, 67, 795, 131);

        var notes = new ArrayList<ScoreNoteEvent>();
        for (int pair = 0; pair < 6; pair++) {
            int dottedX = 115 + 97 * pair;
            int shortX = dottedX + 52;
            int dottedShaft = dottedX - 10;
            int shortShaft = shortX - 10;
            rectangle(gray, dottedShaft, HEAD_Y, dottedShaft + 2, TIP);
            rectangle(gray, shortShaft, HEAD_Y, shortShaft + 2, TIP);
            rectangle(gray, dottedX - 8, HEAD_Y - 4, dottedX + 8, HEAD_Y + 4);
            rectangle(gray, shortX - 8, HEAD_Y - 4, shortX + 8, HEAD_Y + 4);
            rectangle(gray, dottedShaft, TIP - 8, shortShaft + 2, TIP);
            rectangle(gray, dottedShaft, TIP - 22, shortShaft + 2, TIP - 14);
            int endpoint = shortShaft - (pair == 0 && !attachFirstHook ? 5 : 0);
            rectangle(gray, endpoint - 12, TIP - 35, endpoint, TIP - 26);
            rectangle(gray, dottedX + 15, HEAD_Y - 4, dottedX + 18, HEAD_Y - 1);
            notes.add(note(0, dottedX, HEAD_Y, 1, 2, 0, measures));
            notes.add(note(0, shortX, HEAD_Y, 0, pair >= 4 ? 3 : 2, 0, measures));
        }
        rectangle(gray, 722, 95, 742, 103);
        rectangle(gray, 725, 97, 739, 101, (byte) 255);
        rectangle(gray, 722, HEAD_Y, 724, 152);
        rectangle(gray, 748, HEAD_Y - 4, 751, HEAD_Y - 1);
        notes.add(note(1, 732, HEAD_Y, 1, 0, 2, measures));
        return new Fixture(gray, List.copyOf(notes), measures, staffs);
    }

    private static ScoreNoteEvent note(int measure, int x, int y, int dots, int beams,
            int duration, List<MeasureRegion> measures) {
        var r = measures.get(measure);
        float position = (x / (float) WIDTH - r.left()) / (r.right() - r.left());
        return new ScoreNoteEvent(measure, position, 4, 0, 1, y / (float) HEIGHT, false,
                dots, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, duration).withStemDirection(-1);
    }

    private static MeasureRegion region(int left, int right, int top, int bottom) {
        return new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH,
                top / (float) HEIGHT, bottom / (float) HEIGHT);
    }

    private static void rectangle(byte[] gray, int left, int top, int right, int bottom) {
        rectangle(gray, left, top, right, bottom, (byte) 0);
    }

    private static void rectangle(
            byte[] gray, int left, int top, int right, int bottom, byte value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, value);
        }
    }
}
