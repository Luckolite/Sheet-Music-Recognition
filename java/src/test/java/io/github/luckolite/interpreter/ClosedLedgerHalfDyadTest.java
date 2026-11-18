// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ClosedLedgerHalfDyadTest {
    private static final int WIDTH = 400;
    private static final int HEIGHT = 360;
    private static final int GAP = 16;
    private static final int LEFT = 85;
    private static final int RIGHT = 228;
    private static final int STAFF_TOP = 100;
    private static final int STAFF_BOTTOM = STAFF_TOP + 4 * GAP;
    private static final int HEAD = 138;
    private static final int UPPER = STAFF_BOTTOM - 3 * GAP;
    private static final int LOWER = STAFF_BOTTOM + 28;
    private static final int LEDGER = STAFF_BOTTOM + GAP;
    private static final int STEM = HEAD + 10;
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    @Test
    public void hollowHeadsOnOneStemCloseAsTwoHalfNotes() throws IOException {
        drawDyad();
        var before = score();
        var after = capture(before);
        assertEquals(2, after.measures().get(0).cadenceQuarterBeats());
        assertEquals(before.measures().get(1), after.measures().get(1));
        assertEquals(before.notes().get(2), after.notes().get(2));
        assertEquals(List.of(6, -3),
                after.notes().subList(0, 2).stream().map(ScoreNoteEvent::staffStep).toList());

        float[] capacities = {4, 4};
        boolean[] cadences = PrintedCadenceDurations.apply(after, capacities);
        assertTrue(cadences[0]);
        assertEquals(2f, capacities[0], 0);
        assertEquals(4f, capacities[1], 0);
        try (var timing = ScoreNoteTiming.beginTimingSession(after)) {
            for (var head : after.notes().subList(0, 2)) {
                assertEquals(0, head.staffIndex());
                assertEquals(1, head.staffCount());
                assertEquals(1, head.stemDirection());
                assertEquals(ScoreNoteEvent.CLEF_TREBLE, head.clefBottomDiatonic());
                assertEquals(2d, ScoreNoteTiming.resolvedWrittenDurationBeats(
                        head, after.notes(), capacities[0]), 0);
                assertEquals(0d,
                        ScoreNoteTiming.beatInMeasure(head, after.notes(), capacities[0]), 0);
            }
        }
        assertEquals(74, Main.noteIdentity(after.notes().get(0), 0).get("midi"));
        assertEquals(59, Main.noteIdentity(after.notes().get(1), 0).get("midi"));
    }

    @Test
    public void keepsAGenuineLowerStaff() {
        drawDyad();
        var before = score();
        for (int line = 0; line < 5; line++) {
            int y = LOWER - 6 + line * GAP;
            fill(LEFT - 12, y, RIGHT + 8, y + 1, 0);
        }
        assertSame(before, capture(before));
    }

    @Test
    public void keepsTheRawOwnersWhenTheClosingPairOrLedgerIsMissing() {
        var before = score();
        drawDyad();
        fill(RIGHT + 3, STAFF_TOP + 2, RIGHT + 7, STAFF_BOTTOM - 2, 255);
        assertSame(before, capture(before));
        drawDyad();
        fill(HEAD - 19, LEDGER - 1, STEM - 1, LEDGER + 1, 255);
        fill(STEM + 3, LEDGER - 1, HEAD + 20, LEDGER + 1, 255);
        assertSame(before, capture(before));
    }

    @Test
    public void keepsTheRawOwnersWhenTheShaftOrEitherHollowIsMissing() {
        var before = score();
        drawDyad();
        fill(STEM - 1, UPPER + 16, STEM + 3, UPPER + 22, 255);
        assertSame(before, capture(before));
        for (int y : new int[] {UPPER, LOWER}) {
            drawDyad();
            fill(HEAD - 7, y - 4, HEAD + 7, y + 4, 0);
            assertSame(before, capture(before));
        }
    }

    private ScorePageInterpretation capture(ScorePageInterpretation score) {
        return ClosedLedgerHalfDyad.capture(score, gray, WIDTH, HEIGHT);
    }

    private static ScorePageInterpretation score() {
        var closing = new MeasureRegion(
                LEFT / (float) WIDTH, RIGHT / (float) WIDTH, 65f / HEIGHT, 196.2f / HEIGHT);
        var following = new MeasureRegion(
                .21f, .59f, .67f, .95f);
        float position = (HEAD - LEFT) / (float) (RIGHT - LEFT);
        return new ScorePageInterpretation(
                List.of(closing, following),
                List.of(note(0, position, 6, 0, 2, UPPER, 2, ScoreNoteEvent.CLEF_TREBLE, 1),
                        note(0, position, 0, 1, 2, LOWER, 2, ScoreNoteEvent.CLEF_UNKNOWN, 0),
                        note(1, .35f, 0, 0, 1, 300, 1, ScoreNoteEvent.CLEF_TREBLE, 1)),
                0, List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)),
                List.of(), List.of(), List.of(), List.of());
    }

    private static ScoreNoteEvent note(int bar, float position, int step, int staff, int staffCount,
            int y, float duration, int clef, int stem) {
        return new ScoreNoteEvent(bar, position, step, staff, staffCount, y / (float) HEIGHT,
                false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, duration, 1, 0, 0, clef,
                false, 0, false, 0, 0, 1, stem);
    }

    private void drawDyad() {
        Arrays.fill(gray, (byte) 255);
        for (int line = 0; line < 5; line++) {
            int y = STAFF_TOP + line * GAP;
            fill(LEFT - 45, y - 1, RIGHT + 6, y + 1, 0);
        }
        fill(LEFT - 9, STAFF_TOP, LEFT - 7, STAFF_BOTTOM, 0);
        fill(RIGHT - 4, STAFF_TOP, RIGHT - 2, STAFF_BOTTOM, 0);
        fill(RIGHT + 3, STAFF_TOP, RIGHT + 5, STAFF_BOTTOM, 0);
        fill(HEAD - 18, LEDGER - 1, HEAD + 18, LEDGER + 1, 0);
        hollowHead(UPPER);
        hollowHead(LOWER);
        fill(STEM, UPPER - 27, STEM + 2, LOWER + 2, 0);
    }

    private void hollowHead(int center) {
        for (int y = center - 8; y <= center + 8; y++) {
            for (int x = HEAD - 14; x <= HEAD + 14; x++) {
                double dx = x - HEAD;
                double dy = y - center;
                if (dx * dx / 196 + dy * dy / 64 > 1) continue;
                gray[y * WIDTH + x] = (byte) (dx * dx / 49 + dy * dy / 16 <= 1 ? 255 : 0);
            }
        }
    }

    private void fill(int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
