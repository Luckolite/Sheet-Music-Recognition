// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class LongClosedDottedOctaveTest {
    private static final int W = 1060, H = 220;
    private static final List<PlayingTechniqueDetector.Staff> STAFFS =
            List.of(new PlayingTechniqueDetector.Staff(119, 183, 16, 0, 1));

    @Test
    public void joinedDotsDoNotTruncateAnOtherwiseContinuousClosedLine() {
        assertEquals(List.of(0, 1, 1, 0), shifts(source()));
    }

    @Test
    public void anOpenLineOrGapDoesNotAcquireAClosedOctaveSpan() {
        var open = source();
        ink(open, 847, 83, 862, 108, 255);
        assertEquals(0, shifts(open).get(2).intValue());
        var gap = source();
        ink(gap, 356, 70, 392, 81, 255);
        assertEquals(0, shifts(gap).get(2).intValue());
    }

    @Test
    public void closelySpacedDotsContinueToThePrintedTurn() {
        var raw = source();
        ink(raw, 63, 70, 865, 111, 255);
        for (int x = 65; x <= 305; x += 8) ink(raw, x, 74, x + 3, 77, 0);
        for (int y : new int[] {83, 91, 99}) ink(raw, 305, y, 308, y + 3, 0);
        assertEquals(List.of(1, 1, 0),
                OctaveMarkDetector.apply(List.of(), STAFFS, List.of(new MeasureRegion(0, 1, 0, 1)),
                        List.of(note(90), note(295), note(325)), raw, W, H)
                        .stream().map(ScoreNoteEvent::octaveShift).toList());
    }

    @Test
    public void aCurveAcrossTheDotsDoesNotEndAnOctaveLine() {
        var raw = source();
        ink(raw, 63, 58, 865, 111, 255);
        for (int x = 65; x <= 305; x += 8) ink(raw, x, 74, x + 3, 77, 0);
        for (int y = 64; y <= 89; y++) {
            int x = 119 + (y - 64) / 2;
            ink(raw, x, y, x + 3, y, 0);
        }
        for (int y : new int[] {83, 91}) ink(raw, 305, y, 308, y + 3, 0);
        var notes = List.of(note(90), note(295), note(325));
        var bars = List.of(new MeasureRegion(0, 1, 0, 1));
        assertEquals(List.of(1, 1, 0), OctaveMarkDetector.apply(List.of(), STAFFS, bars, notes, raw, W, H)
                .stream().map(ScoreNoteEvent::octaveShift).toList());
    }

    @Test
    public void longCurvedLineRequiresAReturnBeyondTheFirstScanWindows() throws Exception {
        var page = new byte[W * H];
        Arrays.fill(page, (byte) 255);
        ink(page, 41, 70, 61, 94, 0);
        ink(page, 47, 73, 55, 79, 255);
        ink(page, 46, 84, 55, 90, 255);
        for (int x = 65; x <= 125; x += 10) ink(page, x, 75, x + 5, 79, 0);
        for (int y = 67; y <= 90; y++) {
            int x = 128 + Math.round((y - 67) * 1.5f);
            ink(page, x, y, x + 3, y, 0);
        }
        for (int x = 155; x <= 555; x += 10) {
            int y = 75 + (x - 155) / 100;
            ink(page, x, y, x + 5, y + 4, 0);
        }
        ink(page, 557, 87, 560, 90, 0);
        ink(page, 557, 96, 560, 99, 0);

        var detector = OctaveMarkDetector.class;
        var box = Class.forName(detector.getName() + "$InkBox");
        var constructor = box.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var eight = constructor.newInstance(41, 70, 61, 94, 0);
        var closure = detector.getDeclaredMethod(
                "closedCurvedDottedOctave", byte[].class, int.class, int.class, box, float.class);
        closure.setAccessible(true);
        float end = (float) closure.invoke(null, page, W, H, eight, 16f);
        assertTrue("The printed return should end the octave span: " + end,
                end > 560 && end < 575);
        ink(page, 553, 84, 563, 106, 255);
        assertEquals(-1f, (float) closure.invoke(null, page, W, H, eight, 16f), 0f);
    }

    private static List<Integer> shifts(byte[] raw) {
        return OctaveMarkDetector.apply(
                        List.of(),
                        STAFFS,
                        List.of(new MeasureRegion(0, 1, 0, 1)),
                        List.of(note(20), note(90), note(815), note(901)),
                        raw,
                        W,
                        H)
                .stream()
                .map(ScoreNoteEvent::octaveShift)
                .toList();
    }

    private static ScoreNoteEvent note(int x) {
        return new ScoreNoteEvent(0, x / (float) W, 2, 0, 1, 152f / H, false, 0, 1, 2, 0, 1);
    }

    private static byte[] source() {
        var raw = new byte[W * H];
        Arrays.fill(raw, (byte) 255);
        ink(raw, 41, 70, 61, 92, 0);
        ink(raw, 47, 73, 55, 78, 255);
        ink(raw, 46, 83, 55, 88, 255);
        for (int x = 65; x <= 125; x += 10) ink(raw, x, 74, x + 5, 78, 0);
        ink(raw, 135, 74, 162, 78, 0);
        for (int x = 168; x <= 848; x += 10) ink(raw, x, 74, x + 5, 78, 0);
        for (int y : new int[] {83, 91, 99}) ink(raw, 850, y, 853, y + 3, 0);
        return raw;
    }

    private static void ink(byte[] raw, int l, int t, int r, int b, int value) {
        for (int y = t; y <= b; y++) Arrays.fill(raw, y * W + l, y * W + r + 1, (byte) value);
    }
}
