// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RepeatedInlineSharpTest {
    private static final int W = 900, H = 700, GAP = 16;
    private static final int UPPER = 118, LOWER = 292, DONOR = 520;
    private static final int INLINE_X = 436, REPEAT_X = 186;

    @Test
    public void movesKnownSharpToPairedInlineCopies() throws Exception {
        assertEquals(List.of(new ScoreKeyChange(0, -1), new ScoreKeyChange(1, 1)),
                recover(true, true, true, false));
    }

    @Test
    public void needsBothSharpCopiesAndBothDoubleBarsAndIndependentMusic() throws Exception {
        var unchanged = List.of(new ScoreKeyChange(0, -1), new ScoreKeyChange(3, 1));
        assertEquals(unchanged, recover(false, true, true, false));
        assertEquals(unchanged, recover(true, false, true, false));
        assertEquals(unchanged, recover(true, true, false, false));
        assertEquals(unchanged, recover(true, true, true, true));
    }

    private static List<ScoreKeyChange> recover(
            boolean pairedCopy, boolean doubleBar, boolean following, boolean intervening)
            throws Exception {
        byte[] gray = new byte[W * H], labels = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int staff : new int[] {UPPER, LOWER, DONOR})
            for (int row = 0; row < 5; row++)
                Arrays.fill(gray, (staff + row * GAP) * W + 72,
                        (staff + row * GAP) * W + 800, (byte) 180);
        for (int staff : new int[] {UPPER, LOWER})
            for (int y = staff; y <= staff + GAP * 4; y++) {
                gray[y * W + 410] = 0;
                if (staff == UPPER || doubleBar) gray[y * W + 415] = 0;
            }
        sharp(gray, INLINE_X, UPPER);
        if (pairedCopy) sharp(gray, INLINE_X, LOWER);
        sharp(gray, REPEAT_X, DONOR);

        var interpreter = OmrScoreInterpreter.class;
        Class<?> staff = Class.forName(interpreter.getName() + "$Staff");
        Class<?> component = Class.forName(interpreter.getName() + "$Component");
        Class<?> accidental = Class.forName(interpreter.getName() + "$AccidentalCandidate");
        Class<?> repeated = Class.forName(interpreter.getName() + "$RepeatedSharp");
        Object upper = create(staff, (float) UPPER, (float) UPPER + 4 * GAP, (float) GAP);
        Object lower = create(staff, (float) LOWER, (float) LOWER + 4 * GAP, (float) GAP);
        Object donor = create(staff, (float) DONOR, (float) DONOR + 4 * GAP, (float) GAP);
        var count = staff.getDeclaredField("count");
        count.setAccessible(true);
        count.setInt(upper, 2);
        count.setInt(lower, 2);
        var index = staff.getDeclaredField("index");
        index.setAccessible(true);
        index.setInt(lower, 1);
        var symbols = new ArrayList<>();
        var heads = new ArrayList<>();
        for (int y : new int[] {UPPER, LOWER}) {
            var clef = create(component, 600, 96, 124, y - 20, y + 82, 110f, y + 29f);
            symbols.add(create(accidental, clef, (byte) 3));
            if (following)
                heads.add(create(component, 90, 475, 489, y + 7, y + 16, 482f, y + 11f));
            if (intervening)
                heads.add(create(component, 90, 429, 441, y + 51, y + 62, 436f, y + 56f));
        }
        var alias = create(component, 60, 432, 440, UPPER - 9, UPPER + 7, 436f, (float) UPPER);
        heads.add(alias);
        var bars = List.of(region(145, 401, 78, 382), region(420, 614, 78, 382),
                region(626, 798, 78, 382), region(175, 798, 476, 626));
        var repeats = List.of(create(repeated, 3, donor, (float) REPEAT_X));
        var keys = new ArrayList<>(List.of(new ScoreKeyChange(0, -1), new ScoreKeyChange(3, 1)));
        var repair = interpreter.getDeclaredMethod("recoverRepeatedInlineSharp",
                List.class, List.class, List.class, List.class, List.class, List.class,
                byte[].class, byte[].class, int.class, int.class);
        repair.setAccessible(true);
        var owned = repair.invoke(null, keys, repeats, bars, List.of(upper, lower, donor),
                symbols, heads, labels, gray, W, H);
        assertEquals(pairedCopy && doubleBar && following && !intervening
                ? List.of(alias) : List.of(), owned);
        return keys;
    }

    private static Object create(Class<?> type, Object... arguments) throws Exception {
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static MeasureRegion region(int l, int r, int t, int b) {
        return new MeasureRegion(l / (float) W, r / (float) W, t / (float) H, b / (float) H);
    }

    private static void sharp(byte[] gray, int x, int y) {
        for (int dy = -20; dy <= 17; dy++)
            for (int dx : new int[] {-5, -4, 3, 4})
                gray[(y + dy) * W + x + dx] = (byte) 45;
        for (int reference : new int[] {-7, 9})
            for (int dx = -9; dx <= 8; dx++)
                for (int dy = -2; dy <= 1; dy++)
                    gray[(y + reference + dy - dx / 3) * W + x + dx] = 0;
    }
}
