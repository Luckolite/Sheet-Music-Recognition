// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;

public final class OssiaMeasureCountTest {
    private static final int WIDTH = 900, HEIGHT = 650;

    @Test
    public void recognizesAnInsetSmallStaffWithTheSameFinalBars() throws Exception {
        assertTrue(isOssia(12, 710, false, 365, 849));
    }

    @Test
    public void independentHeaderScaleOrTimingKeepsItsOwnSystem() throws Exception {
        assertFalse(isOssia(12, 751, false, 365, 849));
        assertFalse(isOssia(12, 710, true, 365, 849));
        assertFalse(isOssia(16, 710, false, 365, 849));
        assertFalse(isOssia(12, 710, false, 512, 849));
        assertFalse(isOssia(12, 710, false, 365, 811));
    }

    private static boolean isOssia(int size, int centerBar, boolean clef, int followingTop, int end)
            throws Exception {
        byte[] labels = new byte[WIDTH * HEIGHT];
        if (clef)
            for (int y = 178; y < 206; y++)
                for (int x = 570; x < 579; x++)
                    labels[y * WIDTH + x] = OmrMeasurePostProcessor.CLEF_OR_KEY;
        Class<?> processor = OmrMeasurePostProcessor.class;
        Class<?> staff = Class.forName(processor.getName() + "$StaffRun");
        Class<?> system = Class.forName(processor.getName() + "$SystemRun");
        var staffConstructor = staff.getDeclaredConstructor(int.class, int.class, float.class,
                int.class, int.class, List.class, float.class);
        var systemConstructor = system.getDeclaredConstructor(staff);
        var check = processor.getDeclaredMethod("isUpperOssia",
                system, system, byte[].class, int.class, int.class);
        staffConstructor.setAccessible(true);
        systemConstructor.setAccessible(true);
        check.setAccessible(true);
        var small = staffConstructor.newInstance(178, 178 + 4 * size, (float) size,
                560, end, List.of(560, centerBar, end), 0f);
        var full = staffConstructor.newInstance(followingTop, followingTop + 64, 16f, 60, 850,
                List.of(60, 180, 300, 430, 560, 710, 850), 0f);
        return (boolean) check.invoke(null, systemConstructor.newInstance(small),
                systemConstructor.newInstance(full), labels, WIDTH, HEIGHT);
    }
}
