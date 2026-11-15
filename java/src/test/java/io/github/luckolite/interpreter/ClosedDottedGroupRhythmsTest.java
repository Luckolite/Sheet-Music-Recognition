// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedDottedGroupRhythmsTest {
    private static final int W = 1150, H = 430;
    private static final int LEFT = 125, RIGHT = 769, FIRST = 156, SPACING = 39;
    private static final int HEAD = 226, STAFF_BOTTOM = 296;

    @Test
    public void repairsFourClosedDottedGroupsWithMissingHooksAndDots() {
        var score = fixture();
        var gray = page();
        var result = apply(score, gray);
        assertEquals(16, result.notes().size());
        for (int index = 0; index < result.notes().size(); index++) {
            var note = result.notes().get(index);
            assertEquals(index % 2 == 0 ? 2 : 3, note.beamCount());
            var written = ScoreNoteTiming.writtenPlacement(note, result.notes(), 4).orElseThrow();
            assertEquals(index / 2 * .5 + (index % 2 == 0 ? 0 : .375), written.onsetBeats(), 0);
            assertEquals(index % 2 == 0 ? .375 : .125, written.durationBeats(), 0);
        }
        var noHook = gray.clone();
        int hook = FIRST + 5 * SPACING;
        paint(noHook, hook - 22, hook - 11, 313, 324, (byte) 255);
        assertSame(score, apply(score, noHook));
        var noBar = gray.clone();
        paint(noBar, 767, 771, STAFF_BOTTOM - 64, STAFF_BOTTOM, (byte) 255);
        assertSame(score, apply(score, noBar));
    }

    private static ScorePageInterpretation apply(ScorePageInterpretation score, byte[] gray) {
        return ClosedDottedSixteenths.applyClosedMonophonicHooks(score, null, gray, W, H);
    }

    private static ScorePageInterpretation fixture() {
        var events = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 16; i++) {
            boolean dotted = i % 2 == 0;
            int beams = dotted || i == 5 || i == 7 ? 2 : 3;
            int dots = i == 7 ? 1 : dotted && i != 6 ? 1 : 0;
            events.add(note(FIRST + i * SPACING, HEAD, 10, dots, beams, -1));
        }
        return new ScorePageInterpretation(
                List.of(new MeasureRegion(LEFT / (float) W, RIGHT / (float) W, 199f / H, 330f / H)),
                events,
                0,
                List.of(),
                List.of(),
                List.of(new ScoreMeterChange(0, 4, 4)));
    }

    private static ScoreNoteEvent note(int x, int y, int step, int dots, int beams, int stem) {
        return new ScoreNoteEvent(
                0,
                (x - LEFT) / (float) (RIGHT - LEFT),
                step,
                0,
                1,
                y / (float) H,
                false,
                dots,
                beams,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                0,
                1,
                0,
                0,
                ScoreNoteEvent.CLEF_TREBLE,
                false,
                0,
                false,
                0,
                0,
                1,
                stem,
                ScoreNoteEvent.Kind.PITCHED);
    }

    private static byte[] page() {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int line = STAFF_BOTTOM - 64; line <= STAFF_BOTTOM; line += 16)
            paint(gray, 75, 1080, line, line, (byte) 0);
        paint(gray, 768, 770, STAFF_BOTTOM - 64, STAFF_BOTTOM, (byte) 0);
        for (int i = 0; i < 16; i++) {
            int x = FIRST + i * SPACING;
            paint(gray, x - 10, x - 8, HEAD, 349, (byte) 0);
            paint(gray, x - 9, x + 8, HEAD - 5, HEAD + 5, (byte) 0);
            if (i % 2 == 0) {
                for (int dy = -3; dy <= 3; dy++)
                    for (int dx = -3; dx <= 3; dx++)
                        if (dx * dx + dy * dy <= 10) gray[(HEAD - 4 + dy) * W + x + 20 + dx] = 0;
            } else {
                paint(gray, x - 21, x - 8, 314, 323, (byte) 0);
            }
            if (i % 4 == 0) {
                paint(gray, x - 10, x + 3 * SPACING - 8, 342, 349, (byte) 0);
                paint(gray, x - 10, x + 3 * SPACING - 8, 328, 335, (byte) 0);
            }
        }
        return gray;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * W + left, y * W + right + 1, value);
    }
}
