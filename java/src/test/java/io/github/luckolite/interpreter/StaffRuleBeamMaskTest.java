// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public final class StaffRuleBeamMaskTest {
    private static final int WIDTH = 112, HEIGHT = 112;
    private static final float GAP = 16;
    private static final int[] LEFT = {24, 81, 1};
    private static final int[] RIGHT = {64, 77, 1};
    private static final float SLOPE = (RIGHT[1] - LEFT[1]) / (float) (RIGHT[0] - LEFT[0]);

    @Test
    public void railSeamMustSurviveBeyondTheMask() throws Exception {
        var fixture = fixture();
        assertTrue(owns(fixture.gray, fixture.mask));
        var filled = fixture.gray.clone();
        for (int x = 58; x < 64; x++) {
            int tip = tip(x);
            for (int depth = 4; depth <= 16; depth++) filled[(tip - depth) * WIDTH + x] = 0;
        }
        assertFalse(owns(filled, fixture.mask));
    }

    @Test
    public void selectedInkMustStayInsideTheTwoContinuousRailCores() throws Exception {
        var fixture = fixture();
        var broken = fixture.gray.clone();
        for (int x = 40; x <= 46; x++) {
            int tip = tip(x);
            for (int depth = 14; depth <= 21; depth++) broken[(tip - depth) * WIDTH + x] = (byte) 255;
        }
        assertFalse(owns(broken, fixture.mask));

        int[] outside = Arrays.copyOf(fixture.mask, fixture.mask.length + 1);
        int pixel = (tip(49) - 24) * WIDTH + 49;
        outside[outside.length - 1] = pixel;
        fixture.gray[pixel] = 0;
        assertFalse(owns(fixture.gray, outside));
    }

    private static boolean owns(byte[] gray, int[] members) throws Exception {
        Class<?> component = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var constructor = component.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        constructor.setAccessible(true);
        var mask = constructor.newInstance(members.length, 34, 56, 59, 75, 45f, 67f);
        var proof = OmrScoreInterpreter.class.getDeclaredMethod("followsBeamsAtStaffRule",
                byte[].class, byte[].class, int.class, int.class,
                int[].class, int[].class, component, int[].class, float.class, float.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, gray, gray, WIDTH, HEIGHT, LEFT, RIGHT, mask, members, SLOPE, GAP);
    }

    private static Fixture fixture() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        var marks = new ArrayList<Integer>();
        for (int x = 24; x <= 64; x++) {
            int tip = tip(x);
            for (int depth = 2; depth <= 18; depth++) {
                if (x > 57 && depth > 6 && depth < 14) continue;
                int pixel = (tip - depth) * WIDTH + x;
                gray[pixel] = 0;
                if (x >= 34 && x <= 56 && depth >= 5 && depth <= 16) marks.add(pixel);
            }
        }
        return new Fixture(gray, marks.stream().mapToInt(Integer::intValue).toArray());
    }

    private static int tip(int x) {
        return Math.round(LEFT[1] + SLOPE * (x - LEFT[0]));
    }

    private record Fixture(byte[] gray, int[] mask) {}
}
