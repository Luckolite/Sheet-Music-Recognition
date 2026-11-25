// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class LedgerSpaceOctaveTest {
    private static final int WIDTH = 225;
    private static final int HEIGHT = 240;
    private static final int GAP = 16;

    @Test
    public void usesTheSamePrintedShaftAndDoubleBeamFromEitherSide() throws Exception {
        var c = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var s = Class.forName(OmrScoreInterpreter.class.getName() + "$Staff");
        var component = c.getDeclaredConstructors()[0];
        component.setAccessible(true);
        var staff = s.getDeclaredConstructor(float.class, float.class, float.class);
        staff.setAccessible(true);
        var frame = staff.newInstance(118f, 182f, (float) GAP);
        var owner = staff.newInstance(119f, 183f, (float) GAP);
        var admit = OmrScoreInterpreter.class.getDeclaredMethod("shortLedgerSpaceOctave",
                byte[].class, byte[].class, int.class, int.class, c, s, s, List.class);
        admit.setAccessible(true);
        var first = head(component, 80, 78);
        var second = head(component, 109, 62);
        var lowerFirst = head(component, 80, 134);
        var lowerSecond = head(component, 109, 118);
        var heads = List.of(first, second, lowerFirst, lowerSecond);

        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int row = 118; row <= 182; row += GAP) fill(ink, 30, row, 190, row + 1);
        oval(ink, 80, 78);
        oval(ink, 109, 62);
        oval(ink, 80, 134);
        oval(ink, 109, 118);
        fill(ink, 70, 82, 72, 198);
        fill(ink, 99, 66, 101, 193);
        for (int y : new int[] {86, 102}) fill(ink, 69, y, 91, y + 2);
        for (int y : new int[] {70, 86, 102}) fill(ink, 98, y, 120, y + 2);
        for (int x = 70; x <= 101; x++) {
            int y = Math.round(194 - (x - 70) * 5 / 30f);
            fill(ink, x, y, x, y + 4);
            fill(ink, x, y - 11, x, y - 7);
        }
        assertTrue((boolean) admit.invoke(null, ink, ink.clone(), WIDTH, HEIGHT, first, frame, owner, heads));
        assertTrue((boolean) admit.invoke(null, ink, ink.clone(), WIDTH, HEIGHT, second, frame, owner, heads));
        byte[] cut = ink.clone();
        for (int y = 109; y <= 117; y++) Arrays.fill(cut, y * WIDTH + 68, y * WIDTH + 74, (byte) 255);
        assertFalse((boolean) admit.invoke(null, ink, cut, WIDTH, HEIGHT, first, frame, owner, heads));
        assertFalse((boolean) admit.invoke(null, ink, ink.clone(), WIDTH, HEIGHT, first, frame, owner,
                List.of(first, second, lowerSecond)));
    }

    @Test
    public void countsExpandedLedgersWithoutFlatteningAPrintedNinth() throws Exception {
        var componentType = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var component = componentType.getDeclaredConstructors()[0];
        component.setAccessible(true);
        var upper = head(component, 80, 58);
        var lower = head(component, 80, 118);
        var heads = List.of(upper, lower);
        var correct = OmrScoreInterpreter.class.getDeclaredMethod("expandedLedgerDyadPitch",
                byte[].class, byte[].class, int.class, int.class, componentType, List.class,
                float.class, float.class, int.class);
        correct.setAccessible(true);

        byte[] octave = ledgerDyad(101, 84, 67);
        byte[] ninth = ledgerDyad(103, 88, 73, 58);
        assertEquals(15, correct.invoke(null, octave, octave.clone(), WIDTH, HEIGHT, upper, heads,
                182f, (float) GAP, 16));
        assertEquals(16, correct.invoke(null, ninth, ninth.clone(), WIDTH, HEIGHT, upper, heads,
                182f, (float) GAP, 16));
        assertEquals(16, correct.invoke(null, octave, ninth, WIDTH, HEIGHT, upper, heads,
                182f, (float) GAP, 16));

        byte[] severed = octave.clone();
        for (int y = 109; y <= 116; y++)
            Arrays.fill(severed, y * WIDTH + 68, y * WIDTH + 74, (byte) 255);
        assertEquals(16, correct.invoke(null, octave, severed, WIDTH, HEIGHT, upper, heads,
                182f, (float) GAP, 16));
    }

    @Test
    public void recoversAHighNoteOnThePrintedLedgerOfAnOctave() throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var componentType = Class.forName(interpreter.getName() + "$Component");
        var component = componentType.getDeclaredConstructors()[0];
        component.setAccessible(true);
        var staffType = Class.forName(interpreter.getName() + "$Staff");
        var staff = staffType.getDeclaredConstructor(float.class, float.class, float.class);
        staff.setAccessible(true);
        var frame = staff.newInstance(126f, 190f, (float) GAP);
        var upper = head(component, 80, 46);
        var lower = head(component, 80, 102);
        var pair = List.of(upper, lower);
        var recover = interpreter.getDeclaredMethod("filledLedgerLineOctave",
                byte[].class, byte[].class, int.class, int.class, componentType, staffType,
                staffType, List.class);
        recover.setAccessible(true);

        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = 126; y <= 190; y += GAP) fill(ink, 25, y, 190, y + 1);
        oval(ink, 80, 46);
        oval(ink, 80, 102);
        fill(ink, 70, 48, 72, 205);
        for (int y = 46; y <= 110; y += GAP) fill(ink, 65, y - 1, 95, y + 1);
        assertEquals(18, recover.invoke(null, ink, ink.clone(), WIDTH, HEIGHT, upper,
                frame, frame, pair));
        assertEquals(0, recover.invoke(null, ink, ink.clone(), WIDTH, HEIGHT, upper,
                frame, frame, List.of(upper)));

        byte[] severed = ink.clone();
        for (int y = 84; y <= 91; y++) Arrays.fill(severed, y * WIDTH + 67, y * WIDTH + 75, (byte) 255);
        assertEquals(0, recover.invoke(null, ink, severed, WIDTH, HEIGHT, upper, frame, frame, pair));

        byte[] missingLedger = ink.clone();
        for (int y = 77; y <= 79; y++) {
            Arrays.fill(missingLedger, y * WIDTH + 63, y * WIDTH + 69, (byte) 255);
            Arrays.fill(missingLedger, y * WIDTH + 73, y * WIDTH + 97, (byte) 255);
        }
        assertEquals(0, recover.invoke(null, missingLedger, ink, WIDTH, HEIGHT, upper, frame, frame, pair));
    }

    private static byte[] ledgerDyad(int... ledgerRows) {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int row = 118; row <= 182; row += GAP) fill(ink, 30, row, 190, row + 1);
        oval(ink, 80, 58);
        oval(ink, 80, 118);
        fill(ink, 70, 62, 72, 198);
        for (int y : ledgerRows) fill(ink, 66, y - 1, 94, y + 1);
        return ink;
    }

    private static Object head(Constructor<?> c, int x, int y) throws Exception {
        return c.newInstance(270, x - 10, x + 10, y - 8, y + 8, (float) x, (float) y);
    }

    private static void oval(byte[] ink, int cx, int cy) {
        for (int dy = -8; dy <= 8; dy++)
            for (int dx = -11; dx <= 11; dx++)
                if (dx * dx / 121f + dy * dy / 64f <= 1) ink[(cy + dy) * WIDTH + cx + dx] = 0;
    }

    private static void fill(byte[] ink, int x0, int y0, int x1, int y1) {
        for (int y = y0; y <= y1; y++) Arrays.fill(ink, y * WIDTH + x0, y * WIDTH + x1 + 1, (byte) 0);
    }
}
