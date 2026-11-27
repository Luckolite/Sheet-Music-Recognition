// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PaleEnsembleConnectorTest {
    private static final int WIDTH = 1500;
    private static final int HEIGHT = 420;
    private static final List<Integer> UPPER = List.of(142, 330, 550, 1020, 1250, 1450);
    private static final List<Integer> LOWER = List.of(166, 330, 550, 1250, 1450);

    @Test
    public void finiteLeftRuleUnifiesPaleStavesAndRestoresTheirMissingCut() throws Exception {
        var systems = merge(raster(true, false, true));
        assertEquals(1, systems.size());
        var bars = boundaries(systems.get(0));
        assertEquals(7, bars.size());
        assertTrue(bars.stream().anyMatch(x -> Math.abs(x - 780) < 3));
    }

    @Test
    public void opposingStaffMustSupplyItsOwnFiniteDivider() throws Exception {
        var systems = merge(raster(true, false, false));
        assertEquals(1, systems.size());
        assertFalse(boundaries(systems.get(0)).stream().anyMatch(x -> Math.abs(x - 780) < 3));
    }

    @Test
    public void noOpeningRuleAndAFullPageCreaseKeepTwoIndependentRows() throws Exception {
        assertEquals(2, merge(raster(false, false, true)).size());
        assertEquals(2, merge(raster(true, true, true)).size());
    }

    private static byte[] raster(boolean bracket, boolean crease, boolean lowerCut) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int top : new int[] {80, 236})
            for (int rule = 0; rule < 5; rule++)
                Arrays.fill(gray, (top + rule * 16) * WIDTH + 100,
                        (top + rule * 16) * WIDTH + 1451, (byte) 198);
        if (bracket)
            for (int row = crease ? 24 : 80; row <= (crease ? 350 : 300); row++)
                gray[row * WIDTH + 100] = 90;
        for (int row = 80; row <= 144; row++) gray[row * WIDTH + 780] = (byte) 172;
        if (lowerCut)
            for (int row = 236; row <= 300; row++) gray[row * WIDTH + 780] = (byte) 174;
        return gray;
    }

    private static List<?> merge(byte[] gray) throws Exception {
        var type = Class.forName(OmrMeasurePostProcessor.class.getName() + "$StaffRun");
        var constructor = type.getDeclaredConstructor(int.class, int.class, float.class,
                int.class, int.class, List.class, float.class);
        constructor.setAccessible(true);
        var upper = constructor.newInstance(80, 144, 16f, 142, 1450, UPPER, 0f);
        var lower = constructor.newInstance(236, 300, 16f, 166, 1450, LOWER, 0f);
        var merge = OmrMeasurePostProcessor.class.getDeclaredMethod(
                "mergeAlignedStaffs", List.class, byte[].class, int.class, int.class);
        merge.setAccessible(true);
        return (List<?>) merge.invoke(null, List.of(upper, lower), gray, WIDTH, HEIGHT);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> boundaries(Object system) throws Exception {
        var field = system.getClass().getDeclaredField("boundaries");
        field.setAccessible(true);
        return (List<Integer>) field.get(system);
    }
}
