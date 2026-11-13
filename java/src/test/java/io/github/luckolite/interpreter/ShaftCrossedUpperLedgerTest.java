// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class ShaftCrossedUpperLedgerTest {
    private boolean admitted(int missingRule, boolean longRule, boolean detached) throws Exception {
        int width = 240, height = 230;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int y = 53; y <= 69; y++) {
            for (int x = 89; x <= 111; x++) {
                double dx = (x - 100) / 11d, dy = (y - 61) / 8d;
                if (dx * dx + dy * dy <= 1) gray[y * width + x] = 0;
            }
        }
        for (int y = 62; y <= (detached ? 69 : 159); y++)
            for (int x = 89; x <= 91; x++) gray[y * width + x] = 0;
        for (int rule = 1; rule <= 3; rule++) {
            if (rule == missingRule) continue;
            int y = 125 - 16 * rule;
            for (int row = y; row <= y + 2; row++)
                for (int x = 89; x <= (longRule && rule == 2 ? 220 : 113); x++)
                    gray[row * width + x] = 0;
        }
        Class<?> component = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var componentConstructor = component.getDeclaredConstructors()[0];
        componentConstructor.setAccessible(true);
        Object head = componentConstructor.newInstance(275, 89, 111, 53, 69, 100f, 61f);
        Class<?> staff = Class.forName(OmrScoreInterpreter.class.getName() + "$Staff");
        var staffConstructor = staff.getDeclaredConstructors()[0];
        staffConstructor.setAccessible(true);
        Object frame = staffConstructor.newInstance(125f, 189f, 16f);
        var proof = OmrScoreInterpreter.class.getDeclaredMethod(
                "shaftCrossedUpperLedgerRules", byte[].class, int.class, int.class, component, staff);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, gray, width, height, head, frame);
    }

    @Test
    public void threeShortRulesMayBeginAtTheDownstem() throws Exception {
        assertTrue(admitted(0, false, false));
    }

    @Test
    public void missingRulesAndLongUnderlinesDoNotQualify() throws Exception {
        for (int rule = 1; rule <= 3; rule++) assertFalse(admitted(rule, false, false));
        assertFalse(admitted(0, true, false));
    }

    @Test
    public void ledgerFlanksWithoutTheDownstemDoNotQualify() throws Exception {
        assertFalse(admitted(0, false, true));
    }
}
