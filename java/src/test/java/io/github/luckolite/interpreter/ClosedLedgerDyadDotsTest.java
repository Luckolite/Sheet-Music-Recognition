// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedLedgerDyadDotsTest {
    private static final int WIDTH = 700;
    private static final int HEIGHT = 300;
    private static final int LEFT = 37;
    private static final int RIGHT = 635;
    private static final int STAFF_TOP = 120;
    private static final int STAFF_FLOOR = 184;
    private static final int UPPER = 104;
    private static final int LOWER = 144;
    private static final int DOT_OWNER_X = 260;

    @Test
    public void thinLedgerFragmentCannotDotAnyMemberOfAClosedDyad() {
        var fixture = opening();
        var fixed = decode(fixture, fixture.gray());
        assertEquals(24, fixed.notes().size());
        assertEquals(0, fixed.notes().get(8).augmentationDots());
        assertEquals(0, fixed.notes().get(9).augmentationDots());
        assertEquals(fixture.score().notes().subList(10,24), fixed.notes().subList(10,24));
        assertEquals(3, fixed.beamSource().groups().size());
    }

    @Test
    public void detachedDotsAndAnInterruptedRailRemainUntouched() {
        var fixture = opening();
        var separate = fixture.gray().clone();
        box(separate, DOT_OWNER_X + 15, DOT_OWNER_X + 19, UPPER - 2, UPPER + 2, 255);
        assertSame(fixture.score(), decode(fixture, separate));
        var lower = fixture.gray().clone();
        box(lower, DOT_OWNER_X + 20, DOT_OWNER_X + 24, LOWER - 5, LOWER - 1, 0);
        assertSame(fixture.score(), decode(fixture, lower));
        var cut = fixture.gray().clone();
        box(cut, DOT_OWNER_X + 8, DOT_OWNER_X + 22, 191, 213, 255);
        assertSame(fixture.score(), decode(fixture, cut));
    }

    private record Fixture(ScorePageInterpretation score, byte[] gray) {}

    private static ScorePageInterpretation decode(Fixture fixture, byte[] raw) {
        return ClosedSixteenthDyads.apply(fixture.score(), new ScoreMeterChange(0, 4, 4),
                fixture.gray(), raw, WIDTH, HEIGHT, 0);
    }

    private static Fixture opening() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte)255);
        for (int y = STAFF_TOP; y <= STAFF_FLOOR; y += 16)
            box(gray, 20, RIGHT + 30, y, y + 1, 0);
        box(gray, LEFT - 1, LEFT + 1, STAFF_TOP, STAFF_FLOOR, 0);
        box(gray, RIGHT - 1, RIGHT + 1, STAFF_TOP, STAFF_FLOOR, 0);

        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 12; i++) {
            int x = 76 + i * 46;
            int stem = x - 10;
            box(gray, x - 14, x + 14, UPPER, UPPER + 1, 0);
            box(gray, x - 9, x + 7, UPPER - 4, UPPER + 4, 0);
            box(gray, x - 9, x + 7, LOWER - 4, LOWER + 4, 0);
            box(gray, stem - 1, stem + 1, UPPER, 212, 0);
            if (i % 4 == 0) {
                box(gray, stem, stem + 138, 192, 198, 0);
                box(gray, stem, stem + 138, 206, 212, 0);
            }
            notes.add(note(x, UPPER, i == 4));
            notes.add(note(x, LOWER, i == 4));
        }
        box(gray, DOT_OWNER_X + 12, DOT_OWNER_X + 18, UPPER - 1, UPPER + 1, 0);
        box(gray, DOT_OWNER_X + 19, DOT_OWNER_X + 19, UPPER - 1, UPPER + 1, 210);
        box(gray, DOT_OWNER_X + 20, DOT_OWNER_X + 23, UPPER - 2, UPPER + 1, 0);
        var bars = List.of(new MeasureRegion(.03f,.90f,.02f,.10f),
                new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH, 86f / HEIGHT, 217.2f / HEIGHT));
        return new Fixture(new ScorePageInterpretation(bars, notes, 0, List.of(), List.of(),
                List.of(new ScoreMeterChange(1,3,4)), List.of()), gray);
    }

    private static ScoreNoteEvent note(int x, int y, boolean dot) {
        return new ScoreNoteEvent(1, (x - LEFT) / (float) (RIGHT - LEFT), (STAFF_FLOOR - y) / 8,
                0, 1, y / (float) HEIGHT, false, dot ? 1 : 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                0, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE, false, 0, false, 0, 0, 1, -1,
                ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL, 0, null);
    }

    private static void box(byte[] px, int x0, int x1, int y0, int y1, int value) {
        for (int y = y0; y <= y1; y++) {
            Arrays.fill(px, y * WIDTH + x0, y * WIDTH + x1 + 1, (byte)value);
        }
    }
}
