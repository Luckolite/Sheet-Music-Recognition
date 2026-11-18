// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class WideStaffRuleBeamMaskTest {
    private static final int WIDTH = 240, HEIGHT = 230, GAP = 16;
    private static final int LEFT = 50, MIDDLE = 100, RIGHT = 133;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final byte[] labels = new byte[gray.length];

    public WideStaffRuleBeamMaskTest() {
        Arrays.fill(gray, (byte) 255);
        for (int x = LEFT; x <= RIGHT; x++) {
            setColumn(gray, x, tip(x) - 8, tip(x), 0);
            setColumn(gray, x, tip(x) - 20, tip(x) - 13, 0);
            if (x >= MIDDLE - 32 && x <= MIDDLE + 2) {
                setColumn(gray, x, tip(x) - 14, tip(x) - 7, 0);
                setColumn(labels, x, tip(x) - 12, tip(x) - 6,
                        OmrMeasurePostProcessor.NOTEHEAD);
            }
        }
        for (int stem : new int[] {LEFT, MIDDLE, RIGHT}) {
            for (int x = stem; x <= stem + 2; x++) setColumn(gray, x, 63, tip(stem), 0);
            head(stem, 64);
            head(stem, 120);
        }
    }

    @Test
    public void wideStaffRuleMaskRequiresBothRailsAndTheBorrowedShaft() throws Exception {
        assertEquals(1, ownedMasks(gray));
        var missingRail = gray.clone();
        for (int x = LEFT + 22; x <= LEFT + 36; x++) {
            setColumn(missingRail, x, tip(x) - 20, tip(x) - 13, 255);
        }
        assertEquals(0, ownedMasks(missingRail));
        var missingShaft = gray.clone();
        for (int x = MIDDLE - 2; x <= MIDDLE + 3; x++) setColumn(missingShaft, x, 97, 112, 255);
        assertEquals(0, ownedMasks(missingShaft));
    }

    private int ownedMasks(byte[] source) throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var staff = Class.forName(interpreter.getName() + "$Staff");
        var constructor = staff.getDeclaredConstructor(float.class, float.class, float.class);
        constructor.setAccessible(true);
        var owner = constructor.newInstance(104f, 168f, (float) GAP);
        var find = interpreter.getDeclaredMethod("findComponents",
                byte[].class, int.class, int.class, byte.class);
        find.setAccessible(true);
        var heads = (List<?>) find.invoke(null, labels, WIDTH, HEIGHT, OmrMeasurePostProcessor.NOTEHEAD);
        assertEquals(7, heads.size());
        var gate = interpreter.getDeclaredMethod("staffRuleBeamMasks",
                byte[].class, byte[].class, byte[].class, int.class, int.class, List.class, List.class);
        gate.setAccessible(true);
        return ((List<?>) gate.invoke(null, labels, gray, source, WIDTH, HEIGHT, heads, List.of(owner))).size();
    }

    private void head(int shaft, int y) {
        for (int x = shaft; x <= shaft + 19; x++) {
            double horizontal = (x - shaft - 9.5) / 10;
            int inset = (int) Math.round(7 * Math.sqrt(1 - horizontal * horizontal));
            setColumn(gray, x, y - inset, y + inset, 0);
            setColumn(labels, x, y - inset, y + inset, OmrMeasurePostProcessor.NOTEHEAD);
        }
    }

    private static int tip(int x) {
        return 173 + Math.round((x - LEFT) * .08f);
    }

    private static void setColumn(byte[] image, int x, int first, int last, int value) {
        for (int y = first; y <= last; y++) image[y * WIDTH + x] = (byte) value;
    }
}
