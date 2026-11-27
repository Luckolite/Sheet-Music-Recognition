// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SingleBeamAboveTopRuleTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 250;
    private static final int RULE_TOP = 120;
    private static final int GAP = 17;
    private static final int TIP = 99;
    private static final int[] SHAFTS = {304, 347, 386};

    @Test
    public void completeStaffRuleIsNotTheSecondBeam() throws ReflectiveOperationException {
        Page page = new Page();
        page.ink(SHAFTS[0], SHAFTS[2], TIP, TIP + 8);
        assertTrue(page.correctsAt(0));
        assertTrue(page.correctsAt(1));
        assertTrue(page.correctsAt(2));
    }

    @Test
    public void genuineSecondRailRemains() throws ReflectiveOperationException {
        Page page = new Page();
        page.ink(SHAFTS[0], SHAFTS[2], TIP, TIP + 8);
        page.ink(SHAFTS[0], SHAFTS[2], TIP + 11, TIP + 15);
        assertFalse(page.correctsAt(1));
    }

    @Test
    public void unconnectedShaftsDoNotProveAGroup() throws ReflectiveOperationException {
        Page page = new Page();
        page.ink(SHAFTS[1], SHAFTS[1] + GAP + 5, TIP, TIP + 8);
        assertFalse(page.correctsAt(1));
    }

    private static final class Page {
        final byte[] raster = new byte[WIDTH * HEIGHT];

        Page() {
            Arrays.fill(raster, (byte) 255);
            for (int rule = 0; rule < 5; rule++) {
                int bottom = RULE_TOP + rule * GAP;
                ink(15, WIDTH - 16, bottom - 5, bottom);
            }
            for (int shaft : SHAFTS) {
                ink(shaft - 1, shaft + 1, TIP, 170);
                ink(shaft - 15, shaft, 162, 171);
            }
        }

        void ink(int left, int right, int top, int bottom) {
            for (int y = top; y <= bottom; y++)
                Arrays.fill(raster, y * WIDTH + left, y * WIDTH + right + 1, (byte) 30);
        }

        boolean correctsAt(int index) throws ReflectiveOperationException {
            String owner = OmrScoreInterpreter.class.getName();
            Class<?> headType = Class.forName(owner + "$Component");
            Class<?> staffType = Class.forName(owner + "$Staff");
            var component = headType.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class, float.class, float.class);
            component.setAccessible(true);
            var staff = staffType.getDeclaredConstructor(float.class, float.class, float.class);
            staff.setAccessible(true);
            Object frame = staff.newInstance((float) RULE_TOP, (float) (RULE_TOP + 4 * GAP), (float) GAP);
            Object[] heads = new Object[SHAFTS.length];
            for (int i = 0; i < heads.length; i++) {
                int shaft = SHAFTS[i];
                heads[i] = component.newInstance(
                        160, shaft - 15, shaft, 162, 171, shaft - 7f, 166.5f);
            }
            var detector = OmrScoreInterpreter.class.getDeclaredMethod(
                    "oneBeamOnStaffRule",
                    byte[].class, byte[].class, int.class, int.class, headType, staffType, List.class);
            detector.setAccessible(true);
            return (boolean) detector.invoke(
                    null, new byte[raster.length], raster, WIDTH, HEIGHT, heads[index], frame, List.of(heads));
        }
    }
}
