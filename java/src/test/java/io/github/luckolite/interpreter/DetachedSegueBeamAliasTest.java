// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class DetachedSegueBeamAliasTest {
    private static final int WIDTH = 640, HEIGHT = 320, GAP = 16, FLOOR = 160;
    private static final int LEFT = 70, RIGHT = 570;
    private static final List<MeasureRegion> MEASURES = List.of(new MeasureRegion(
            LEFT / (float) WIDTH, RIGHT / (float) WIDTH, 69f / HEIGHT, 202f / HEIGHT));
    private static final List<PlayingTechniqueDetector.Staff> STAFFS = List.of(
            new PlayingTechniqueDetector.Staff(FLOOR - 4 * GAP, FLOOR, GAP, 0, 1));

    @Test public void removesOnlyDetachedPrintedLettering() {
        var first = note(204, 176, -2, 2);
        var descender = note(241, 193, -4, 3);
        var printed = note(346, 123, 8, 2).withStemDirection(-1);
        var notes = List.of(first, descender, printed);
        var pixels = detachedWord();
        assertEquals(List.of(printed), apply("segue", notes, pixels));
        assertSame(notes, apply("sempre", notes, pixels));

        var attached = List.of(first, descender.withStemDirection(-1), printed);
        assertSame(attached, apply("segue", attached, pixels));
        paint(pixels, 197, 210, 170, 186, (byte) 255);
        assertSame(notes, apply("segue", notes, pixels));
    }

    @Test public void preservesProposalsWhenMeasureCropDoesNotContainStaff() {
        var first = note(204, 176, -2, 2);
        var descender = note(241, 193, -4, 3);
        var notes = List.of(first, descender);
        var word = new PlayingTechniqueDetector.Word("segue", 190f / WIDTH, 164f / HEIGHT,
                300f / WIDTH, 202f / HEIGHT);
        var clipped = new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH,
                110f / HEIGHT, 202f / HEIGHT);
        assertSame(notes, FingeringAnnotationFilter.detachedSegueBeamAliases(
                List.of(word), STAFFS, List.of(clipped), notes, detachedWord(), WIDTH, HEIGHT));
    }

    private static List<ScoreNoteEvent> apply(String text, List<ScoreNoteEvent> notes, byte[] gray) {
        var word = new PlayingTechniqueDetector.Word(text, 190f / WIDTH, 164f / HEIGHT,
                300f / WIDTH, 202f / HEIGHT);
        return FingeringAnnotationFilter.apply(List.of(word), STAFFS, MEASURES, notes, gray, WIDTH, HEIGHT);
    }

    private static ScoreNoteEvent note(int x, int y, int step, int beams) {
        return new ScoreNoteEvent(0, (x - LEFT) / (float) (RIGHT - LEFT), step, 0, 1,
                y / (float) HEIGHT, false, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0);
    }

    private static byte[] detachedWord() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = FLOOR; y >= FLOOR - 4 * GAP; y -= GAP)
            paint(gray, 30, 600, y, y, (byte) 0);
        paint(gray, 197, 210, 170, 186, (byte) 0);
        paint(gray, 215, 227, 170, 186, (byte) 0);
        paint(gray, 234, 287, 170, 186, (byte) 0);
        paint(gray, 235, 247, 186, 197, (byte) 0);
        return gray;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, byte tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, tone);
    }
}
