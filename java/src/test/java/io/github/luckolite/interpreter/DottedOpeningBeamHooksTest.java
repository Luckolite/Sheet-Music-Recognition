// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class DottedOpeningBeamHooksTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 256;

    @Test
    public void retainsTerminalHookAndCompletesOnlyAnUnclaimedColumn() {
        for (boolean complete : new boolean[] {false, true}) {
            var score = score(complete, false);
            var raw = source(true, true);
            var result = DottedOpeningBeamHooks.apply(score, raw, raw, WIDTH, HEIGHT);
            var group = result.beamSource().groups().get(0);
            assertEquals(List.of(1, 3), group.bridges());
            assertEquals(
                    List.of(new ScoreBeamSource.Hook(2, 3, ScoreBeamSource.Direction.BACKWARD)),
                    group.hooks());
            assertEquals(score.notes(), result.notes());
            assertSame(result, DottedOpeningBeamHooks.apply(result, raw, raw, WIDTH, HEIGHT));
        }
    }

    @Test
    public void requiresIndependentHookAndClosedLowerRails() {
        var score = score(false, false);
        var complete = source(true, true);
        for (var erased : new byte[][] {source(false, true), source(true, false)})
            assertSame(score, DottedOpeningBeamHooks.apply(score, complete, erased, WIDTH, HEIGHT));
    }

    @Test
    public void leavesAnotherGroupOrDetachedFlagInControlOfItsOwnNote() {
        var score = score(false, true);
        var raw = source(true, true);
        assertSame(score, DottedOpeningBeamHooks.apply(score, raw, raw, WIDTH, HEIGHT));
    }

    private static ScorePageInterpretation score(boolean complete, boolean detached) {
        var notes = List.of(note(55, 1, 0), note(95, 2, 1), note(135, 3, 0));
        var first = column(0);
        var second = column(1);
        var third = column(2);
        var group =
                new ScoreBeamSource.Group(
                        "synthetic",
                        complete ? List.of(first, second, third) : List.of(first, second),
                        complete ? List.of(1, 3) : List.of(1),
                        List.of());
        var beams =
                ScoreBeamSource.capture(
                        0, notes.size(), List.of(group), detached ? List.of(third) : List.of());
        return new ScorePageInterpretation(List.of(new MeasureRegion(0, 1, .15f, .6625f)), notes)
                .withBeamSource(beams);
    }

    private static ScoreNoteEvent note(int x, int beams, int dots) {
        return new ScoreNoteEvent(
                        0,
                        x / (float) WIDTH,
                        2,
                        0,
                        1,
                        112f / HEIGHT,
                        false,
                        dots,
                        beams,
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                        0)
                .withStemDirection(1);
    }

    private static ScoreBeamSource.Column column(int index) {
        return new ScoreBeamSource.Column(List.of(new ScoreBeamSource.Anchor(0, index)));
    }

    private static byte[] source(boolean hook, boolean joined) {
        byte[] raw = new byte[WIDTH * HEIGHT];
        Arrays.fill(raw, (byte) 255);
        for (int x : new int[] {55, 95, 135}) {
            fill(raw, x - 5, x + 9, 110, 114);
            fill(raw, x + 8, x + 10, 56, 112);
        }
        fill(raw, 63, 145, 56, 64);
        fill(raw, 103, 145, 68, 76);
        if (hook) fill(raw, 133, 145, 81, 89);
        if (!joined)
            for (int y = 55; y <= 77; y++)
                Arrays.fill(raw, y * WIDTH + 118, y * WIDTH + 125, (byte) 255);
        return raw;
    }

    private static void fill(byte[] raw, int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(raw, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
