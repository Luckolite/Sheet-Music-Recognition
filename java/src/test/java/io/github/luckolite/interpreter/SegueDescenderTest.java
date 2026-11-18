// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class SegueDescenderTest {
    private static final int WIDTH = 720, HEIGHT = 280;
    private static final int LEFT = 110, RIGHT = 640;
    private static final MeasureRegion BAR =
            new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH, 80f / HEIGHT, 229f / HEIGHT);
    private static final PlayingTechniqueDetector.Staff STAFF =
            new PlayingTechniqueDetector.Staff(112, 176, 16, 0, 1);
    private static final PlayingTechniqueDetector.Word CAPTION =
            new PlayingTechniqueDetector.Word("3 segue", 404f / WIDTH, 179f / HEIGHT, 560f / WIDTH, 223f / HEIGHT);

    @Test
    public void removesTheDescenderFromACompleteSixteenthNoteBar() {
        var notes = notes();
        assertEquals(notes.subList(0, 12), apply(notes, printedCaption(), List.of(CAPTION)));
    }

    @Test
    public void largeStaffCaptionKeepsTheOriginalInkThreshold() {
        var notes = notes();
        int width = 1170, height = 455;
        var wideStaff = new PlayingTechniqueDetector.Staff(182, 286, 26, 0, 1);
        var template = printedCaption();
        byte[] paper = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int stock = template[(y * HEIGHT / height) * WIDTH + x * WIDTH / width] & 255;
                paper[y * width + x] = (byte) (stock == 0 ? 165 : 255);
            }
        }
        assertEquals(notes.subList(0, 12), FingeringAnnotationFilter.apply(
                List.of(CAPTION), List.of(wideStaff), List.of(BAR), notes, paper, width, height));

        for (int pixel = 0; pixel < paper.length; pixel++)
            if ((paper[pixel] & 255) == 165) paper[pixel] = (byte) 166;
        assertEquals(notes, FingeringAnnotationFilter.apply(
                List.of(CAPTION), List.of(wideStaff), List.of(BAR), notes, paper, width, height));
    }

    @Test
    public void aCompetingPrintedStemOrMissingDescenderKeepsTheHead() {
        var notes = notes();
        var shaft = printedCaption();
        paint(shaft, 497, 500, 151, 211, 0);
        assertEquals(notes, apply(notes, shaft, List.of(CAPTION)));
        var missingTail = printedCaption();
        paint(missingTail, 479, 506, 202, 217, 255);
        assertEquals(notes, apply(notes, missingTail, List.of(CAPTION)));
    }

    @Test
    public void unresolvedMusicOrMissingTextKeepsTheHead() {
        var incomplete = new ArrayList<>(notes());
        incomplete.remove(0);
        assertEquals(incomplete, apply(incomplete, printedCaption(), List.of(CAPTION)));
        var notes = notes();
        assertEquals(notes, apply(notes, printedCaption(), List.of()));
    }

    private static List<ScoreNoteEvent> apply(
            List<ScoreNoteEvent> notes, byte[] gray, List<PlayingTechniqueDetector.Word> words) {
        return FingeringAnnotationFilter.apply(words, List.of(STAFF), List.of(BAR), notes, gray, WIDTH, HEIGHT);
    }

    private static List<ScoreNoteEvent> notes() {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 12; i++) notes.add(note(144 + 40 * i, 111, 8, 2, 0).withStemDirection(-1));
        notes.add(note(490, 193, -2, 0, 4));
        return notes;
    }

    private static ScoreNoteEvent note(int x, int y, int step, int beams, float duration) {
        return new ScoreNoteEvent(0, (x - LEFT) / (float) (RIGHT - LEFT), step, 0, 1,
                y / (float) HEIGHT, false, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, duration);
    }

    private static byte[] printedCaption() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        paint(gray, 421, 431, 185, 198, 0);
        paint(gray, 443, 453, 185, 199, 0);
        paint(gray, 462, 473, 185, 199, 0);
        paint(gray, 483, 502, 185, 200, 0);
        paint(gray, 488, 496, 189, 195, 255);
        paint(gray, 497, 501, 201, 211, 0);
        paint(gray, 486, 499, 211, 213, 0);
        paint(gray, 514, 526, 185, 199, 0);
        paint(gray, 535, 547, 185, 199, 0);
        return gray;
    }

    private static void paint(byte[] raster, int left, int right, int top, int bottom, int gray) {
        for (int y = top; y <= bottom; y++) Arrays.fill(raster, y * WIDTH + left, y * WIDTH + right + 1, (byte) gray);
    }
}
