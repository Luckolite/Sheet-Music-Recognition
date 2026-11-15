// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

public class UpperLedgerSixthOwnershipTest {
    private static final int W = 260, H = 320, X = 126;
    private static final int UPPER = 100, LOWER = 140, SHAFT = 116;
    private static final float GAP = 16f;
    private final byte[] gray = new byte[W * H], labels = new byte[W * H];

    public UpperLedgerSixthOwnershipTest() {
        Arrays.fill(gray, (byte) 255);
        oval(UPPER);
        oval(LOWER);
        paint(gray, SHAFT - 1, SHAFT + 1, UPPER, 228, 0);
        paint(gray, 110, 143, 115, 116, 0);
        paint(gray, 110, 143, 163, 164, 0);
        paint(gray, 111, 144, UPPER, UPPER + 1, 0);
    }

    @Test
    public void sharedSixthWithTwoExposedLedgersQualifies() throws Exception {
        assertTrue(proved(gray, true));
        assertFalse(proved(gray, false));
    }

    @Test
    public void rawLedgerAndShaftEachHaveToSurvive() throws Exception {
        var missingLedger = gray.clone();
        paint(missingLedger, SHAFT + 3, 143, 159, 168, 255);
        assertFalse(proved(missingLedger, true));

        var cutStem = gray.clone();
        paint(cutStem, SHAFT - 2, SHAFT + 2, 171, 181, 255);
        assertFalse(proved(cutStem, true));
    }

    private boolean proved(byte[] raw, boolean includeLower) throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var component = Class.forName(interpreter.getName() + "$Component");
        var staff = Class.forName(interpreter.getName() + "$Staff");
        var constr = staff.getDeclaredConstructor(float.class, float.class, float.class);
        constr.setAccessible(true);
        var physical = constr.newInstance(180f, 244f, GAP);
        var components =
                interpreter.getDeclaredMethod(
                        "findComponents", byte[].class, int.class, int.class, byte.class);
        components.setAccessible(true);
        var list =
                (List<?>) components.invoke(null, labels, W, H, OmrMeasurePostProcessor.NOTEHEAD);
        var y = component.getDeclaredMethod("centerY");
        y.setAccessible(true);
        Object upper = list.stream().min(Comparator.comparingInt(h -> center(h, y))).orElseThrow();
        var gate =
                interpreter.getDeclaredMethod(
                        "sharedWideUpperLedgerChord",
                        byte[].class,
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        component,
                        staff,
                        staff,
                        List.class,
                        List.class);
        gate.setAccessible(true);
        return (boolean)
                gate.invoke(
                        null,
                        labels,
                        gray,
                        raw,
                        W,
                        H,
                        upper,
                        physical,
                        physical,
                        List.of(physical),
                        includeLower ? list : List.of(upper));
    }

    private static int center(Object note, java.lang.reflect.Method y) {
        try {
            return Math.round((float) y.invoke(note));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void oval(int cy) {
        for (int y = cy - 9; y <= cy + 9; y++) {
            for (int x = X - 12; x <= X + 12; x++) {
                double dx = (x - X) / 11.6;
                double dy = (y - cy + .30 * (x - X)) / 8.3;
                if (dx * dx + dy * dy > 1) continue;
                labels[y * W + x] = OmrMeasurePostProcessor.NOTEHEAD;
                gray[y * W + x] = 0;
            }
        }
    }

    private static void paint(byte[] plane, int left, int right, int top, int bottom, int ink) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(plane, y * W + left, y * W + right + 1, (byte) ink);
    }
}
