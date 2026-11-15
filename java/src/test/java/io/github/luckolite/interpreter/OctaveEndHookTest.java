// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.luckolite.interpreter.OctaveMarkDetectorTest.*;
import java.util.List;

public class OctaveEndHookTest {
    @Test
    public void parenthesizedLowerDirectionWithHook() {
        assertEquals(-1, OctaveMarkDetector.shift("(8vb) 」"));
    }

    @Test
    public void upperAndDoubleDirectionsWithHook() {
        assertEquals(1, OctaveMarkDetector.shift("(8va)┐"));
        assertEquals(-2, OctaveMarkDetector.shift("15mb┘"));
    }

    @Test
    public void hookDoesNotTurnNumberIntoDirection() {
        assertEquals(0, OctaveMarkDetector.shift("8」"));
    }

    @Test
    public void embeddedHookAndProseStayRejected() {
        assertEquals(0, OctaveMarkDetector.shift("8」vb"));
        assertEquals(0, OctaveMarkDetector.shift("8vb lyrics」"));
    }

    @Test
    public void isolatedContinuationOnlyShiftsItsFirstChord() {
        var n =
                apply(
                        page(),
                        List.of(word("(8vb) 」", 130, 370)),
                        List.of(note(150, 1), note(300, 1), note(150, 0)));
        assertEquals(List.of(-1, 0, 0), n.stream().map(ScoreNoteEvent::octaveShift).toList());
    }

    @Test
    public void hookDoesNotExtendDashedSpan() {
        var g = page();
        dash(g, 120, 370, 390);
        var n =
                apply(
                        g,
                        List.of(word("8vb┘", 80, 370)),
                        List.of(note(150, 1), note(300, 1), note(500, 1)));
        assertEquals(List.of(-1, -1, 0), n.stream().map(ScoreNoteEvent::octaveShift).toList());
    }

    @Test
    public void terminalHookOwnsItsHorizontalDashOnly() throws Exception {
        var gray = page();
        dash(gray, 120, 350, 55);
        for (int x = 354; x <= 366; x++)
            for (int y = 55; y <= 56; y++) gray[y * W + x] = 0;
        for (int y = 55; y <= 66; y++) gray[y * W + 366] = 0;
        var ink = new java.util.BitSet();
        var detect = OctaveMarkDetector.class.getDeclaredMethod("dashEnd", byte[].class,
                int.class, int.class, float.class, float.class, float.class, float.class,
                int.class, java.util.BitSet.class);
        detect.setAccessible(true);
        assertTrue((float) detect.invoke(null, gray, W, H, 120f, 55f, 55f, 16f, 3, ink) >= 366);
        assertTrue(ink.get(55 * W + 360));
        assertFalse(ink.get(66 * W + 366));
    }

    @Test
    public void detachedPrintedDottedCornerRequiresAllThreeDownwardDots() throws Exception {
        var gray = page();
        dash(gray, 120, 366, 55);
        for (int y : new int[] {55, 61, 70, 79})
            for (int row = y; row <= y + (y == 55 ? 2 : 5); row++)
                for (int x = 369; x <= 373; x++) gray[row * W + x] = 0;
        var detect =
                OctaveMarkDetector.class.getDeclaredMethod(
                        "extendOverDottedHook",
                        byte[].class,
                        int.class,
                        int.class,
                        float.class,
                        float.class,
                        float.class,
                        float.class);
        detect.setAccessible(true);
        float incoming = 364 + 16 * .55f;
        float result = (float) detect.invoke(null, gray, W, H, incoming, 55f, 55f, 16f);
        assertTrue(result >= 375f && result < 379f);
        for (int y = 70; y <= 75; y++)
            for (int x = 369; x <= 373; x++) gray[y * W + x] = (byte) 255;
        assertEquals(incoming, (float) detect.invoke(null, gray, W, H, incoming, 55f, 55f, 16f), 0);
    }

    @Test
    public void dottedTurnJustPastTheLastDashPointsTowardTheStaff() throws Exception {
        var gray = page();
        dash(gray, 120, 366, 55);
        for (int top : new int[] {59, 67, 75, 84})
            for (int y = top; y < top + 5; y++)
                for (int x = 366; x <= 368; x++) gray[y * W + x] = 0;
        var hook =
                OctaveMarkDetector.class.getDeclaredMethod(
                        "displacedDownwardDottedHook",
                        byte[].class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        float.class);
        hook.setAccessible(true);
        assertTrue((boolean) hook.invoke(null, gray, W, H, 364, 53, 60, 16f));
        for (int y = 67; y < 83; y++) for (int x = 366; x <= 368; x++) gray[y * W + x] = (byte) 255;
        assertFalse((boolean) hook.invoke(null, gray, W, H, 364, 53, 60, 16f));
    }
}
