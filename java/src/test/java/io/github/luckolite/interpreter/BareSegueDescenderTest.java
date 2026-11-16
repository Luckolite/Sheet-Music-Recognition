// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

public final class BareSegueDescenderTest {
    private static final int WIDTH = 720, HEIGHT = 260;
    private static final int CAPTION_LEFT = 384, CAPTION_RIGHT = 487;
    private static final int ALIAS_X = 430, ALIAS_Y = 173;
    private static final MeasureRegion BAR = new MeasureRegion(
            64f / WIDTH, 640f / WIDTH, 82f / HEIGHT, 204f / HEIGHT);
    private static final PlayingTechniqueDetector.Staff STAFF =
            new PlayingTechniqueDetector.Staff(96, 160, 16, 0, 1);
    private static final PlayingTechniqueDetector.Word CAPTION = new PlayingTechniqueDetector.Word(
            "segue", CAPTION_LEFT / (float) WIDTH, 163f / HEIGHT,
            CAPTION_RIGHT / (float) WIDTH, 198f / HEIGHT);
    @Test public void requiresTheWordItsDescenderAndTheCompleteRun() {
        var notes = overlayNotes();
        var raw = source();
        assertEquals(notes, recover(raw, notes, List.of()));
        var erased = raw.clone();
        paint(erased, ALIAS_X - 10, ALIAS_X + 26, 183, 194, 255);
        assertEquals(notes, recover(erased, notes, List.of(CAPTION)));
        var independent = raw.clone();
        paint(independent, ALIAS_X + 8, ALIAS_X + 10, ALIAS_Y - 40, ALIAS_Y + 4, 0);
        assertEquals(notes, recover(independent, notes, List.of(CAPTION)));
        notes.remove(3);
        assertEquals(notes, recover(raw, notes, List.of(CAPTION)));
    }

    @Test public void aBareSegueDescenderLeavesOnlyTheTwelveWrittenNotes() {
        assertEquals(sixteenths(), recover(source(), overlayNotes(), List.of(CAPTION)));
    }

    @Test public void preservesAnIndependentGenuineTriplet() {
        var triplet = List.of(head(0, 111, 1, 3, 0), head(1, 151, 1, 3, 0), head(2, 191, 1, 3, 0));
        assertEquals(triplet, recover(source(), triplet, List.of(CAPTION)));
    }

    private static List<ScoreNoteEvent> recover(byte[] gray, List<ScoreNoteEvent> notes,
            List<PlayingTechniqueDetector.Word> words) {
        return FingeringAnnotationFilter.apply(words, List.of(STAFF), List.of(BAR), notes,
                gray, WIDTH, HEIGHT);
    }

    private static ArrayList<ScoreNoteEvent> overlayNotes() {
        var notes = new ArrayList<>(sixteenths());
        notes.add(head(-2, ALIAS_X, 0, 1, 4));
        notes.sort(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure));
        return notes;
    }

    private static List<ScoreNoteEvent> sixteenths() {
        var result = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 12; i++) result.add(head(i + 1, 110 + 37 * i, 2, 1, 0));
        return result;
    }

    private static ScoreNoteEvent head(int step, int x, int beams, int tuplet, int base) {
        int y = base == 4 ? ALIAS_Y : 145 - Math.min(8, step) * 6;
        return new ScoreNoteEvent(0, (x - 64f) / 576f, step, 0, 1, y / (float) HEIGHT, false, 0,
                beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, base, tuplet, 0, 0,
                ScoreNoteEvent.CLEF_TREBLE, false, 0, false, 0, 0, tuplet == 3 ? 2 : 1,
                beams == 0 ? 0 : -1);
    }

    private static byte[] source() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        paint(gray, CAPTION_LEFT + 5, CAPTION_LEFT + 18, 168, 183, 0);
        paint(gray, CAPTION_LEFT + 22, CAPTION_LEFT + 35, 168, 183, 0);
        paint(gray, CAPTION_LEFT + 37, CAPTION_LEFT + 64, 168, 174, 0);
        paint(gray, CAPTION_LEFT + 43, CAPTION_LEFT + 52, 174, 191, 0);
        paint(gray, CAPTION_LEFT + 70, CAPTION_LEFT + 87, 168, 183, 0);
        return gray;
    }

    private static void paint(byte[] gray, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++) Arrays.fill(gray, y * WIDTH + left,
                y * WIDTH + right + 1, (byte) value);
    }
}
