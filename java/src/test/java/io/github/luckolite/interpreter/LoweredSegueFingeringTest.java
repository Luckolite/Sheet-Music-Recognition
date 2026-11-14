// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

/** Original raster with independent notation and caption glyphs. */
public class LoweredSegueFingeringTest {
    private static final int WIDTH = 870, HEIGHT = 350, GAP = 16;
    private static final int LEFT = 120, RIGHT = 700, STAFF_BOTTOM = 189;
    private static final List<MeasureRegion> MEASURES =
            List.of(
                    new MeasureRegion(
                            LEFT / (float) WIDTH,
                            RIGHT / (float) WIDTH,
                            93f / HEIGHT,
                            223f / HEIGHT));
    private static final List<PlayingTechniqueDetector.Staff> STAFFS =
            List.of(
                    new PlayingTechniqueDetector.Staff(
                            STAFF_BOTTOM - GAP * 4, STAFF_BOTTOM, GAP, 0, 1));

    @Test
    public void removesOnlyIndependentlyOwnedTextInTheCompleteBar() {
        var notes = notes();
        var result = apply(words(), notes, image());
        assertEquals(notes.stream().filter(n -> n.stemDirection() == -1).toList(), result);
    }

    @Test
    public void eitherMissingGlyphOwnerBlocksRemoval() {
        var notes = notes();
        var missingWordLobe = image();
        paint(missingWordLobe, 473, 502, 199, 224, (byte) 255);
        assertSame(notes, apply(words(), notes, missingWordLobe));
        var missingNumeral = image();
        paint(missingNumeral, 319, 332, 177, 188, (byte) 255);
        assertSame(notes, apply(words(), notes, missingNumeral));
    }

    @Test
    public void requiresBothFingeringWordNeighborsAndThePrintedClose() {
        var notes = notes();
        assertSame(notes, apply(words().subList(1, 4), notes, image()));
        var open = image();
        for (int y = STAFF_BOTTOM - 4 * GAP; y <= STAFF_BOTTOM; y++) {
            if ((STAFF_BOTTOM - y) % GAP != 0) paint(open, RIGHT, RIGHT + 2, y, y, (byte) 255);
        }
        assertSame(notes, apply(words(), notes, open));
    }

    private static List<ScoreNoteEvent> apply(
            List<PlayingTechniqueDetector.Word> words, List<ScoreNoteEvent> notes, byte[] image) {
        return FingeringAnnotationFilter.apply(
                words, STAFFS, MEASURES, notes, image, WIDTH, HEIGHT);
    }

    private static List<PlayingTechniqueDetector.Word> words() {
        return List.of(
                word("21", 220, 162, 285, 188),
                word("3", 310, 162, 338, 188),
                word("21 3", 366, 161, 424, 188),
                word("segue", 430, 190, 540, 227));
    }

    private static PlayingTechniqueDetector.Word word(
            String text, int left, int top, int right, int bottom) {
        return new PlayingTechniqueDetector.Word(
                text,
                left / (float) WIDTH,
                top / (float) HEIGHT,
                right / (float) WIDTH,
                bottom / (float) HEIGHT);
    }

    private static List<ScoreNoteEvent> notes() {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 12; i++) notes.add(note(165 + i * 46, 105, 10, 2, -1, false));
        notes.add(note(323, 182, 1, 2, 0, false));
        notes.add(note(444, 206, -2, 0, 0, false));
        notes.add(note(485, 206, -2, 0, 0, true));
        return notes.stream()
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                .toList();
    }

    private static ScoreNoteEvent note(int x, int y, int step, int beams, int stem, boolean tie) {
        return new ScoreNoteEvent(
                0,
                (x - LEFT) / (float) (RIGHT - LEFT),
                step,
                0,
                1,
                y / (float) HEIGHT,
                tie,
                0,
                beams,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                beams == 0 ? 4 : 0,
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
                ScoreNoteEvent.Kind.PITCHED,
                ScoreNoteEvent.VisualSize.FULL,
                0,
                null);
    }

    private static byte[] image() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = STAFF_BOTTOM; y >= STAFF_BOTTOM - 4 * GAP; y -= GAP)
            paint(gray, 90, 760, y, y, (byte) 0);
        paint(gray, RIGHT, RIGHT + 2, STAFF_BOTTOM - 4 * GAP, STAFF_BOTTOM, (byte) 0);
        for (int i = 0; i < 12; i++) {
            int x = 165 + i * 46;
            paint(gray, x - 10, x - 8, 105, 169, (byte) 0);
            paint(gray, x - 9, x + 8, 100, 110, (byte) 0);
            if (i % 4 != 0) continue;
            paint(gray, x - 10, x + 3 * 46 - 8, 163, 169, (byte) 0);
            paint(gray, x - 10, x + 3 * 46 - 8, 150, 156, (byte) 0);
        }
        paint(gray, 319, 330, 178, 186, (byte) 0);
        paint(gray, 437, 450, 199, 215, (byte) 0);
        paint(gray, 455, 467, 199, 215, (byte) 0);
        paint(gray, 474, 506, 199, 218, (byte) 0);
        paint(gray, 513, 525, 199, 215, (byte) 0);
        return gray;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, byte tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, tone);
    }
}
