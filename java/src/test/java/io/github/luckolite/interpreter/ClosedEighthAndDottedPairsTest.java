// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedEighthAndDottedPairsTest {
    private static final int W = 1150, H = 430;
    private static final int LEFT = 125, RIGHT = 702, FIRST = 170, SPACING = 39;
    private static final int HEAD = 226, STAFF_BOTTOM = 296;

    @Test
    public void eighthStartsAndDottedRunsRequireBothSourceCaps() {
        var score = fixture();
        var gray = page();
        var result = apply(score, gray);
        assertEquals(14, result.notes().size());
        for (int index = 0; index < result.notes().size(); index++) {
            var note = result.notes().get(index);
            int slot = index % 7;
            boolean dotted = slot % 2 == 1;
            assertEquals(slot == 0 ? 1 : dotted ? 2 : 3, note.beamCount());
            var written = ScoreNoteTiming.writtenPlacement(note, result.notes(), 4).orElseThrow();
            double start = index / 7 * 2;
            if (slot > 0) start += .5 + (slot - 1) / 2 * .5 + (dotted ? 0 : .375);
            assertEquals(start, written.onsetBeats(), 0);
            assertEquals(slot == 0 ? .5 : dotted ? .375 : .125, written.durationBeats(), 0);
        }
        var noHook = gray.clone();
        int hook = FIRST + 6 * SPACING;
        paint(noHook, hook - 22, hook - 11, 313, 324, (byte) 255);
        assertSame(score, apply(score, noHook));
        var noBar = gray.clone();
        paint(noBar, 708, 716, STAFF_BOTTOM - 64, STAFF_BOTTOM, (byte) 255);
        assertSame(score, apply(score, noBar));
    }

    @Test
    public void restoresAnUpStemTerminalHookInAMixedStemMeasure() {
        var original = fixture();
        var notes = new ArrayList<>(original.notes());
        var gray = page();
        int start = FIRST + 7 * SPACING;
        int finish = FIRST + 13 * SPACING;
        int upHead = 2 * (STAFF_BOTTOM - 32) - HEAD;
        paint(gray, start - 23, finish + 12, 0, H - 1, (byte) 255);
        for (int line = STAFF_BOTTOM - 64; line <= STAFF_BOTTOM; line += 16)
            paint(gray, start - 23, finish + 12, line, line, (byte) 0);
        paint(gray, start + 8, finish + 10, 179, 186, (byte) 0);
        paint(gray, start + 8, finish + 10, 193, 200, (byte) 0);
        for (int i = 7; i < 14; i++) {
            int x = FIRST + i * SPACING;
            int slot = i % 7;
            boolean dotted = slot % 2 == 1;
            int beams = slot == 0 ? 1 : dotted || i == 13 ? 2 : 3;
            notes.set(i, note(x, upHead, -2, dotted ? 1 : 0, beams, 1));
            paint(gray, x + 8, x + 10, 179, upHead, (byte) 0);
            paint(gray, x - 8, x + 9, upHead - 5, upHead + 5, (byte) 0);
            if (dotted) {
                for (int dy = -3; dy <= 3; dy++)
                    for (int dx = -3; dx <= 3; dx++)
                        if (dx * dx + dy * dy <= 10) gray[(upHead - 4 + dy) * W + x + 20 + dx] = 0;
            } else if (slot != 0) {
                paint(gray, x - 3, x + 10, 205, 214, (byte) 0);
            }
        }
        var score =
                original.withNotes(
                        notes, java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
        var fixed = apply(score, gray);
        assertEquals(3, fixed.notes().get(13).beamCount());
        var terminal =
                ScoreNoteTiming.writtenPlacement(fixed.notes().get(13), fixed.notes(), 4)
                        .orElseThrow();
        assertEquals(3.875, terminal.onsetBeats(), 0);
        assertEquals(.125, terminal.durationBeats(), 0);

        paint(gray, finish - 3, finish + 7, 205, 214, (byte) 255);
        assertSame(score, apply(score, gray));
    }

    private static ScorePageInterpretation apply(ScorePageInterpretation score, byte[] gray) {
        return ClosedDottedSixteenths.apply(score, gray, W, H);
    }

    private static ScorePageInterpretation fixture() {
        var events = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 14; i++) {
            int slot = i % 7;
            boolean dotted = slot % 2 == 1;
            int beams = slot == 0 ? 1 : dotted || i == 6 || i == 11 ? 2 : 3;
            events.add(note(FIRST + i * SPACING, HEAD, 10, dotted || i == 11 ? 1 : 0, beams, -1));
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
        paint(gray, 711, 713, STAFF_BOTTOM - 64, STAFF_BOTTOM, (byte) 0);
        for (int i = 0; i < 14; i++) {
            int x = FIRST + i * SPACING;
            paint(gray, x - 10, x - 8, HEAD, 349, (byte) 0);
            paint(gray, x - 9, x + 8, HEAD - 5, HEAD + 5, (byte) 0);
            if (i % 7 % 2 == 1) {
                for (int dy = -3; dy <= 3; dy++)
                    for (int dx = -3; dx <= 3; dx++)
                        if (dx * dx + dy * dy <= 10) gray[(HEAD - 4 + dy) * W + x + 20 + dx] = 0;
            } else if (i % 7 != 0) {
                paint(gray, x - 21, x - 8, 314, 323, (byte) 0);
            }
            if (i % 7 == 0) {
                paint(gray, x - 10, x + 6 * SPACING - 8, 342, 349, (byte) 0);
                paint(gray, x - 10, x + 6 * SPACING - 8, 328, 335, (byte) 0);
            }
        }
        paint(gray, FIRST + 5 * SPACING - 10, FIRST + 6 * SPACING - 8, 328, 349, (byte) 0);
        return gray;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * W + left, y * W + right + 1, value);
    }
}
