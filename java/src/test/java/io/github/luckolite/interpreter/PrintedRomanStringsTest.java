// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class PrintedRomanStringsTest {
    private static final int WIDTH = 320, HEIGHT = 280;
    private static final MeasureRegion REGION = new MeasureRegion(.12f, .89f, .30f, .78f);
    private static final PlayingTechniqueDetector.Staff SINGLE_STAFF =
            new PlayingTechniqueDetector.Staff(157, 243, 21, 0, 1);
    private static final List<ScoreNoteEvent> HEADS = List.of(note(155), note(210));
    private static final List<PrintedRomanStrings.Mark> LABELS = List.of(
            new PrintedRomanStrings.Mark(0, "I", 1, "ABOVE"),
            new PrintedRomanStrings.Mark(1, "II", 2, "ABOVE"));
    private final byte[] raster = new byte[WIDTH * HEIGHT];

    public PrintedRomanStringsTest() {
        Arrays.fill(raster, (byte) 255);
        serif(150, 76);
        serif(144, 103);
        serif(156, 103);
    }

    @Test
    public void verifiesTwoRowsOnSeparateOrMergedRawWords() {
        assertEquals(LABELS, capture(separate(), HEADS, events(73)));
        assertEquals(LABELS, capture(List.of(word("\u2161I", 126, 71, 172, 133)), HEADS, events(73)));
        assertEquals(LABELS, capture(List.of(word("I", 138, 72, 161, 102),
                word("118", 131, 101, 167, 132)), HEADS, events(73)));
    }

    @Test
    public void rejectsAnOcrWordAfterItsOpticalSerifWasErased() {
        ink(144, 76, 156, 81, (byte) 255);
        assertTrue(capture(separate(), HEADS, events(73)).isEmpty());
    }

    @Test
    public void rejectsDigitOrBadOcrWithoutTwoVerifiedRows() {
        assertTrue(capture(List.of(word("4", 130, 72, 167, 133)), HEADS, events(73)).isEmpty());
        assertTrue(capture(List.of(word("I", 138, 72, 161, 102),
                word("pizz", 131, 101, 167, 132)), HEADS, events(73)).isEmpty());
    }

    @Test
    public void keepsTwoPlayableAssignmentsUnresolved() {
        assertTrue(capture(separate(), HEADS, events(76)).isEmpty());
    }

    @Test
    public void rejectsInkOwnedByTwoAttacks() {
        assertTrue(capture(separate(), List.of(note(155), note(210), note(155), note(210)),
                List.of(event(0, 155, 85, 0), event(1, 210, 73, 0),
                        event(2, 155, 85, 1), event(3, 210, 73, 1))).isEmpty());
    }

    @Test
    public void rejectsAnOverlappingForeignStaff() {
        var anotherStaff = new PlayingTechniqueDetector.Staff(57, 104, 10, 1, 2);
        assertTrue(PrintedRomanStrings.capture(raster, WIDTH, HEIGHT, separate(),
                new ScorePageInterpretation(List.of(REGION), HEADS), events(73),
                () -> List.of(SINGLE_STAFF, anotherStaff)).isEmpty());
    }

    @Test
    public void rejectsCueAndDuplicateNoteOwners() {
        assertTrue(capture(separate(),
                List.of(HEADS.get(0).withVisualSize(ScoreNoteEvent.VisualSize.CUE), HEADS.get(1)),
                events(73)).isEmpty());
        assertTrue(capture(separate(), HEADS, List.of(event(0, 155, 85, 0), event(1, 210, 73, 0),
                event(1, 210, 73, 1))).isEmpty());
    }

    private static List<SheetInterpreter.Word> separate() {
        return List.of(word("I", 138, 72, 161, 102), word("\u2161", 132, 101, 167, 132));
    }

    private List<PrintedRomanStrings.Mark> capture(List<SheetInterpreter.Word> words,
                                                  List<ScoreNoteEvent> notes, List<Map<String, Object>> events) {
        return PrintedRomanStrings.capture(raster, WIDTH, HEIGHT, words,
                new ScorePageInterpretation(List.of(REGION), notes), events, () -> List.of(SINGLE_STAFF));
    }

    private static ScoreNoteEvent note(int y) {
        return new ScoreNoteEvent(0, .43f, 5, 0, 1, y / (float) HEIGHT, false).withClef(ScoreNoteEvent.CLEF_TREBLE);
    }

    private static List<Map<String, Object>> events(int lowerMidi) {
        return List.of(event(0, 155, lowerMidi + 12, 0), event(1, 210, lowerMidi, 0));
    }

    private static Map<String, Object> event(int owner, int y, int midi, int beat) {
        return Map.of("sourceNoteIndex", owner, "measureIndex", 0, "staffIndex", 0, "staffCount", 1,
                "x", 150, "y", y, "midi", midi, "startBeat", beat, "durationBeats", .25);
    }

    private static SheetInterpreter.Word word(String text, int l, int t, int r, int b) {
        return new SheetInterpreter.Word(text, l / (float) WIDTH, t / (float) HEIGHT,
                r / (float) WIDTH, b / (float) HEIGHT);
    }

    private void serif(int x, int y) {
        ink(x - 5, y, x + 5, y + 4, (byte) 0);
        ink(x - 1, y + 4, x + 2, y + 20, (byte) 0);
        ink(x - 5, y + 19, x + 5, y + 23, (byte) 0);
    }

    private void ink(int l, int t, int r, int b, byte color) {
        for (int y = t; y <= b; y++) Arrays.fill(raster, y * WIDTH + l, y * WIDTH + r + 1, color);
    }
}
