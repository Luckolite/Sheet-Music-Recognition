// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BorrowedRailEighthTest {
    private static final int WIDTH = 820;
    private static final int HEIGHT = 280;
    private static final int LEFT = 38;
    private static final int RIGHT = 750;
    private static final int FLOOR = 158;
    private static final ScoreMeterChange COMMON = new ScoreMeterChange(0, 4, 4);

    @Test
    public void reverseShaftInAClosedSixteenthQuartetDoesNotSupplyAnEighth() {
        var fixture = phrase();
        var result = decode(fixture, fixture.ink);
        assertEquals(fixture.realNotes, result.notes());
        assertEquals(fixture.score.measures(), result.measures());
    }

    @Test
    public void independentInkAndBrokenRailRejectTheSuppression() {
        var fixture = phrase();
        var extra = fixture.ink.clone();
        rectangle(extra, 636, 644, 224, 228, 0);
        assertSame(fixture.score, decode(fixture, extra));
        var cut = fixture.ink.clone();
        rectangle(cut, 624, 635, 197, 222, 255);
        assertSame(fixture.score, decode(fixture, cut));
    }

    private record Phrase(ScorePageInterpretation score, List<ScoreNoteEvent> realNotes, byte[] ink) {}

    private static ScorePageInterpretation decode(Phrase fixture, byte[] raw) {
        return ClosedMonophonicSixteenths.removeBorrowedRailEighth(
                fixture.score, COMMON, fixture.ink, raw, WIDTH, HEIGHT);
    }

    private static Phrase phrase() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = FLOOR - 64; y <= FLOOR; y += 16) rectangle(ink, LEFT, RIGHT + 15, y, y + 1, 0);
        rectangle(ink, LEFT, LEFT + 2, FLOOR - 64, FLOOR, 0);
        rectangle(ink, RIGHT - 1, RIGHT + 1, FLOOR - 64, FLOOR, 0);
        var real = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 16; i++) {
            int x = 70 + 42 * i;
            int y = 135 - 3 * (i % 4);
            int stem = x - 10;
            rectangle(ink, x - 9, x + 7, y - 4, y + 4, 0);
            rectangle(ink, stem - 1, stem + 1, i == 14 ? y - 26 : y, 221, 0);
            if (i % 4 == 0) {
                rectangle(ink, stem, stem + 126, 202, 208, 0);
                rectangle(ink, stem, stem + 126, 215, 221, 0);
            }
            real.add(note(x, y, -1, 2));
        }
        var all = new ArrayList<>(real);
        all.add(14,note(641,210,1,1));
        var bar = new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH, 60f / HEIGHT, 192f / HEIGHT);
        return new Phrase(new ScorePageInterpretation(List.of(bar),all,0,List.of(),List.of(),
                List.of(),List.of()),List.copyOf(real),ink);
    }

    private static ScoreNoteEvent note(int x, int y, int stem, int beams) {
        return new ScoreNoteEvent(0, (x - LEFT) / (float) (RIGHT - LEFT), Math.round((FLOOR - y) / 8f),
                0, 1, y / (float) HEIGHT, false, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                0, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE, false, 0, false, 0, 0, 1, stem,
                ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL, 0, null);
    }

    private static void rectangle(byte[] pixels, int x0, int x1, int y0, int y1, int intensity) {
        for (int y = y0; y <= y1; y++) {
            Arrays.fill(pixels, y * WIDTH + x0, y * WIDTH + x1 + 1, (byte) intensity);
        }
    }
}
