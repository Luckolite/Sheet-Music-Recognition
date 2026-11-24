// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class PaleCounterOctaveTest {
    private static final int WIDTH = 480, HEIGHT = 225;
    private static final List<PlayingTechniqueDetector.Staff> STAFFS =
            List.of(new PlayingTechniqueDetector.Staff(119, 183, 16, 0, 1));

    @Test
    public void paleCounterClosuresStillRequireTheClosedPrintedLine() {
        var page = source();
        assertEquals(List.of(0, 1, 1, 0), shifts(page));
        var open = page.clone();
        ink(open, 270, 78, 282, 104, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(open));
        ink(page, 119, 68, 122, 69, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(page));
    }

    @Test
    public void shortPaleRibbonNeedsARealDownwardTerminal() {
        int width = 720;
        var page = new byte[width * HEIGHT];
        Arrays.fill(page, (byte) 255);
        var original = source();
        for (int y = 62; y <= 88; y++)
            System.arraycopy(original, y * WIDTH + 100, page, y * width + 100, 23);
        for (int x = 126; x <= 576; x += 9)
            for (int y = 68; y <= 72; y++)
                Arrays.fill(page, y * width + x, y * width + x + 6, (byte) 0);
        for (int y = 72; y <= 82; y++)
            Arrays.fill(page, y * width + 579, y * width + 582, (byte) 0);
        for (int y : new int[] {88, 96, 104})
            for (int row = y; row <= y + 3; row++)
                Arrays.fill(page, row * width + 579, row * width + 582, (byte) 0);

        assertEquals(List.of(0, 1, 1, 0), shortRibbonShifts(page, width));
        var notes = List.of(137, 565).stream().map(x -> shortRibbonNote(x, width)).toList();
        var spans = new java.util.ArrayList<OctaveMarkDetector.StaffSpan>();
        OctaveMarkDetector.apply(List.of(), shortRibbonStaff(), List.of(new MeasureRegion(0, 1, 0, 1)),
                notes, page, width, HEIGHT, spans);
        assertEquals(List.of(new OctaveMarkDetector.StaffSpan(0, 0, 0, 1, 1)), spans);

        for (int y = 73; y < 108; y++)
            Arrays.fill(page, y * width + 579, y * width + 582, (byte) 255);
        assertEquals(List.of(0, 0, 0, 0), shortRibbonShifts(page, width));
    }

    private static List<Integer> shortRibbonShifts(byte[] page, int width) {
        var notes = List.of(70, 137, 565, 621).stream().map(x -> shortRibbonNote(x, width)).toList();
        return OctaveMarkDetector.apply(List.of(), shortRibbonStaff(), List.of(new MeasureRegion(0, 1, 0, 1)),
                notes, page, width, HEIGHT).stream().map(ScoreNoteEvent::octaveShift).toList();
    }

    private static ScoreNoteEvent shortRibbonNote(int x, int width) {
        return new ScoreNoteEvent(0, x / (float) width, 2, 0, 1, 152f / HEIGHT,
                false, 0, 1, 2, 0, 1);
    }

    private static List<PlayingTechniqueDetector.Staff> shortRibbonStaff() {
        return List.of(new PlayingTechniqueDetector.Staff(130, 194, 16, 0, 1));
    }

    private static List<Integer> shifts(byte[] raw) {
        return OctaveMarkDetector.apply(
                        List.of(),
                        STAFFS,
                        List.of(new MeasureRegion(0, 1, 0, 1)),
                        List.of(note(70), note(114), note(226), note(329)),
                        raw,
                        WIDTH,
                        HEIGHT)
                .stream()
                .map(ScoreNoteEvent::octaveShift)
                .toList();
    }

    private static ScoreNoteEvent note(int x) {
        return new ScoreNoteEvent(
                0, x / (float) WIDTH, 2, 0, 1, 152f / HEIGHT, false, 0, 1, 2, 0, 1);
    }

    private static byte[] source() {
        var raw = new byte[WIDTH * HEIGHT];
        Arrays.fill(raw, (byte) 255);
        ink(raw, 100, 62, 122, 88, 0);
        ink(raw, 105, 66, 118, 72, 255);
        ink(raw, 105, 77, 118, 84, 255);
        ink(raw, 119, 68, 122, 69, 238);
        ink(raw, 100, 80, 104, 81, 238);
        for (int x = 126; x <= 270; x += 9) ink(raw, x, 73, x + 5, 76, 0);
        for (int y : new int[] {82, 90, 98}) ink(raw, 272, y, 275, y + 3, 0);
        return raw;
    }

    private static void ink(byte[] raw, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(raw, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
