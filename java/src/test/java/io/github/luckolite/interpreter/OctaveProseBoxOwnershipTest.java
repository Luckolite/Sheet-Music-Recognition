// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class OctaveProseBoxOwnershipTest {
    private static final int WIDTH = 320, HEIGHT = 530;
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    @Test
    public void alignedParagraphOwnsEmbeddedNumeralShapeOnly() {
        Arrays.fill(gray, (byte) 255);
        mark(90);
        mark(278);
        var candidateRow = word("Lowercase letters continue along the paragraph.", 50, 85, 281, 112);
        var preceding = word("Printed introductory remarks continue here.", 48, 58, 276, 83);

        assertEquals(List.of(1, 1), shifts(List.of()));
        assertEquals(List.of(1, 1), shifts(List.of(candidateRow)));
        assertEquals(List.of(0, 1), shifts(List.of(candidateRow, preceding)));
    }

    @Test
    public void misalignedDistantAndShortTextCannotBlockAPrintedOctave() {
        Arrays.fill(gray, (byte) 255);
        mark(90);
        mark(278);
        var candidateRow = word("Lowercase letters continue along the paragraph.", 50, 85, 281, 112);
        for (var different : List.of(
                word("Printed introductory remarks continue here.", 80, 58, 315, 83),
                word("Printed introductory remarks continue here.", 48, 20, 276, 43),
                word("F al fine.", 48, 58, 276, 83))) {
            assertEquals(List.of(1, 1), shifts(List.of(candidateRow, different)));
        }
        var above = word("Lowercase letters continue along the paragraph.", 50, 83, 281, 105);
        assertEquals(List.of(1, 1), shifts(List.of(
                above, word("Printed introductory remarks continue here.", 48, 59, 276, 81))));
    }

    private List<Integer> shifts(List<PlayingTechniqueDetector.Word> words) {
        var staffs = List.of(
                new PlayingTechniqueDetector.Staff(150, 198, 12, 0, 1),
                new PlayingTechniqueDetector.Staff(338, 386, 12, 0, 1));
        var regions = List.of(
                new MeasureRegion(0, 1, 115f / HEIGHT, 226f / HEIGHT),
                new MeasureRegion(0, 1, 303f / HEIGHT, 412f / HEIGHT));
        return OctaveMarkDetector.apply(words, staffs, regions,
                List.of(note(0,170), note(1,358)), gray, WIDTH, HEIGHT)
                .stream().map(ScoreNoteEvent::octaveShift).toList();
    }

    private static ScoreNoteEvent note(int measure, int y) {
        return new ScoreNoteEvent(measure, 100f / WIDTH, 2, 0, 1, y / (float) HEIGHT,
                false, 0, 1, 2, 0, 1);
    }

    private static PlayingTechniqueDetector.Word word(String text, int left, int top, int right, int bottom) {
        return new PlayingTechniqueDetector.Word(text, left / (float) WIDTH,
                top / (float) HEIGHT, right / (float) WIDTH, bottom / (float) HEIGHT);
    }

    private void mark(int y) {
        for (int cy : new int[]{y + 5, y + 14})
            for (int yy = cy - 5; yy <= cy + 5; yy++)
                for (int x = 55; x <= 65; x++) {
                    double r = Math.pow((x - 60) / 5d, 2) + Math.pow((yy - cy) / 5d, 2);
                    if (r <= 1.1 && r >= .3) gray[yy * WIDTH + x] = 0;
                }
        for (int x = 76; x < 210; x += 8)
            for (int xx = x; xx < x + 3; xx++) gray[(y + 3) * WIDTH + xx] = 0;
    }
}
