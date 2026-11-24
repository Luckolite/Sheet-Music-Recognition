// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedCommonTimeDottedSixteenthsTest {
    private static final int W = 1150, H = 430;
    private static final int LEFT = 125, RIGHT = 783, FIRST = 170, SPACING = 39;
    private static final int HEAD = 226, STAFF_BOTTOM = 296;
    private static final int ALIAS = FIRST + 2 * SPACING - 10;

    @Test
    public void completeRunRequiresItsMissingHooksAndStemOwnedBeamInk() {
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
        var noBand = gray.clone();
        paint(noBand, ALIAS + 5, ALIAS + 15, 333, 348, (byte) 255);
        assertSame(score, apply(score, noBand));
        var noHook = gray.clone();
        int hook = FIRST + 5 * SPACING;
        paint(noHook, hook - 22, hook - 11, 313, 324, (byte) 255);
        assertSame(score, apply(score, noHook));
        var noBar = gray.clone();
        paint(noBar, 790, 798, STAFF_BOTTOM - 64, STAFF_BOTTOM, (byte) 255);
        assertSame(score, apply(score, noBar));
    }

    @Test
    public void opposedOuterHooksRequireBothPlanesAndThePrintedClose() {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int line = STAFF_BOTTOM - 64; line <= STAFF_BOTTOM; line += 16)
            paint(gray, 75, 1080, line, line, (byte) 0);
        paint(gray, 793, 795, STAFF_BOTTOM - 64, STAFF_BOTTOM, (byte) 0);
        var events = new ArrayList<ScoreNoteEvent>();
        int first = FIRST - 14, spacing = 40;
        for (int slot = 0; slot < 16; slot++) {
            int x = first + slot * spacing;
            boolean down = slot < 8;
            boolean dotted = slot % 2 == 0;
            boolean missing = slot == 1 || slot == 15;
            int head = down ? STAFF_BOTTOM - 64 : STAFF_BOTTOM;
            int axis = x + (down ? -9 : 9);
            int tip = down ? 349 : 230;
            paint(gray, x - 9, x + 9, head - 5, head + 5, (byte) 0);
            paint(gray, axis - 1, axis + 1, Math.min(tip, head), Math.max(tip, head), (byte) 0);
            if (!dotted)
                paint(gray, axis - 13, axis + 1, down ? 313 : 256, down ? 322 : 265, (byte) 0);
            if (slot % 4 == 0) {
                paint(
                        gray,
                        axis - 1,
                        axis + 3 * spacing + 1,
                        down ? 341 : 230,
                        down ? 349 : 238,
                        (byte) 0);
                paint(
                        gray,
                        axis - 1,
                        axis + 3 * spacing + 1,
                        down ? 327 : 244,
                        down ? 335 : 252,
                        (byte) 0);
            }
            events.add(
                    note(
                            x,
                            head,
                            down ? 8 : 0,
                            dotted ? 1 : 0,
                            dotted || missing ? 2 : 3,
                            down ? -1 : 1));
        }
        var measure = new MeasureRegion(LEFT / (float) W, RIGHT / (float) W, 199f / H, 330f / H);
        var meter = new ScoreMeterChange(0, 4, 4);
        var score =
                new ScorePageInterpretation(
                        List.of(measure), events, 0, List.of(), List.of(), List.of(meter));
        var kept =
                ClosedDottedSixteenths.applyClosedMonophonicHooks(score, meter, gray, gray, W, H);
        assertEquals(3, kept.notes().get(1).beamCount());
        assertEquals(3, kept.notes().get(15).beamCount());
        var noHook = gray.clone();
        int lastAxis = first + 15 * spacing + 9;
        paint(noHook, lastAxis - 13, lastAxis - 4, 256, 265, (byte) 255);
        assertSame(
                score,
                ClosedDottedSixteenths.applyClosedMonophonicHooks(
                        score, meter, noHook, gray, W, H));
        assertSame(
                score,
                ClosedDottedSixteenths.applyClosedMonophonicHooks(
                        score, meter, gray, noHook, W, H));
    }

    @Test
    public void closedDottedPairsBeforeHalfDistinguishHookFromAugmentationDot() {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int line = STAFF_BOTTOM - 64; line <= STAFF_BOTTOM; line += 16)
            paint(gray, 75, 1080, line, line, (byte) 0);
        int right = 542, first = 161, spacing = 40, half = first + spacing * 8;
        paint(gray, right, right + 2, STAFF_BOTTOM - 64, STAFF_BOTTOM, (byte) 0);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 8; slot++) {
            int x = first + slot * spacing;
            int axis = x + 9;
            boolean dotted = slot % 2 == 0;
            paint(gray, x - 9, x + 9, STAFF_BOTTOM - 5, STAFF_BOTTOM + 5, (byte) 0);
            paint(gray, axis - 1, axis + 1, 230, STAFF_BOTTOM, (byte) 0);
            if (dotted) {
                paint(gray, x + 19, x + 23, STAFF_BOTTOM - 10, STAFF_BOTTOM - 6, (byte) 0);
                paint(gray, axis - 1, axis + spacing + 1, 230, 238, (byte) 0);
                paint(gray, axis - 1, axis + spacing + 1, 244, 252, (byte) 0);
            } else {
                paint(gray, axis - 13, axis + 1, 256, 265, (byte) 0);
            }
            notes.add(cadenceNote(x, STAFF_BOTTOM, right, 0, dotted || slot == 1 ? 1 : 0,
                    dotted || slot == 1 ? 2 : 3, 0));
        }
        int halfY = STAFF_BOTTOM - 8;
        paint(gray, half - 9, half + 8, halfY - 5, halfY + 5, (byte) 0);
        paint(gray, half - 6, half + 5, halfY - 3, halfY + 3, (byte) 255);
        paint(gray, half + 7, half + 9, 230, halfY, (byte) 0);
        notes.add(cadenceNote(half, halfY, right, 1, 0, 0, 2));
        var bar = new MeasureRegion(LEFT / (float) W, right / (float) W, 199f / H, 330f / H);
        var score = new ScorePageInterpretation(List.of(bar), notes);
        var meter = new ScoreMeterChange(0, 4, 4);
        var result = ClosedDottedSixteenths.applyClosedMonophonicHooks(score, meter, gray, gray, W, H);
        assertEquals(3, result.notes().get(1).beamCount());
        assertEquals(0, result.notes().get(1).augmentationDots());
        assertEquals(2, ScoreNoteTiming.writtenPlacement(result.notes().get(8), result.notes(), 4)
                .orElseThrow().onsetBeats(), 0);
        assertEquals(notes.subList(2, 9), result.notes().subList(2, 9));

        var trueDot = gray.clone();
        int owner = first + spacing;
        paint(trueDot, owner + 19, owner + 23, STAFF_BOTTOM - 10, STAFF_BOTTOM - 6, (byte) 0);
        assertSame(score, ClosedDottedSixteenths.applyClosedMonophonicHooks(score, meter, gray, trueDot, W, H));
        var noHook = gray.clone();
        paint(noHook, owner - 5, owner + 5, 256, 265, (byte) 255);
        assertSame(score, ClosedDottedSixteenths.applyClosedMonophonicHooks(score, meter, gray, noHook, W, H));
    }

    private static ScoreNoteEvent cadenceNote(int x, int y, int right, int step, int dots, int beams, int base) {
        return new ScoreNoteEvent(
                0, (x - LEFT) / (float) (right - LEFT), step, 0, 1, y / (float) H,
                false, dots, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, base, 1, 0, 0,
                ScoreNoteEvent.CLEF_TREBLE, false, 0, false, 0, 0, 1, 1, ScoreNoteEvent.Kind.PITCHED);
    }

    private static ScorePageInterpretation apply(ScorePageInterpretation score, byte[] gray) {
        return ClosedDottedSixteenths.apply(score, gray, W, H);
    }

    private static ScorePageInterpretation fixture() {
        var events = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 16; i++) {
            boolean dotted = i % 2 == 0;
            int beams = dotted || i == 5 || i == 7 ? 2 : 3;
            events.add(note(FIRST + i * SPACING, HEAD, 10, dotted ? 1 : 0, beams, -1));
        }
        events.add(2, note(ALIAS, 340, 0, 0, 3, 0));
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
        paint(gray, 793, 795, STAFF_BOTTOM - 64, STAFF_BOTTOM, (byte) 0);
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
        paint(gray, ALIAS - 23, ALIAS + 23, 334, 349, (byte) 0);
        return gray;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, byte value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * W + left, y * W + right + 1, value);
    }
}
