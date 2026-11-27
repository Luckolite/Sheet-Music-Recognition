// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExpandedUpperLedgerSpaceTest {
    private static final int WIDTH = 220, HEIGHT = 350;
    private static final int FLOOR = 310, GAP = 16, X = 100;
    private static final int[] LEDGERS = {229, 212, 195, 178, 161};

    @Test
    public void bothPlanesProveTheSpaceAboveExpandedLedgers() throws Exception {
        byte[] source = ink(151);
        assertEquals(19, read(source, source.clone(), 151));
    }

    @Test
    public void eitherPlaneMissingTheNearestLedgerWithholdsTheChange() throws Exception {
        byte[] source = ink(151), missing = source.clone();
        line(missing, LEDGERS[LEDGERS.length - 1], (byte) 255);
        assertEquals(20, read(source, missing, 151));
        assertEquals(20, read(missing, source, 151));
    }

    @Test
    public void matchingLedgerThroughTheHeadRetainsItsPitch() throws Exception {
        byte[] source = ink(144);
        line(source, 144, (byte) 0);
        assertEquals(20, read(source, source.clone(), 144));
    }

    @Test
    public void isolatedAccidentalSpurDoesNotSupplyAHollowLedger() throws Exception {
        byte[] source = hollowLedgerInk();
        assertTrue(disconnectedHollowWing(source));
        for (int y = 146; y <= 150; y++) Arrays.fill(source, y * WIDTH + 85, y * WIDTH + 91, (byte) 0);
        assertFalse(disconnectedHollowWing(source));
    }

    @Test
    public void incompleteInnerLineCannotProveAHollowSpace() throws Exception {
        byte[] source = hollowLedgerInk();
        line(source, 161, (byte) 255);
        assertFalse(disconnectedHollowWing(source));
    }

    private static byte[] hollowLedgerInk() {
        byte[] plane = new byte[WIDTH * HEIGHT];
        Arrays.fill(plane, (byte) 255);
        line(plane, 161, (byte) 0);
        line(plane, 178, (byte) 0);
        for (int y = 146; y <= 150; y++) Arrays.fill(plane, y * WIDTH + 80, y * WIDTH + 86, (byte) 0);
        return plane;
    }

    private static boolean disconnectedHollowWing(byte[] ink) throws Exception {
        var headType = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var constructor = headType.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        constructor.setAccessible(true);
        var head = constructor.newInstance(180, X - 11, X + 11, 143, 159, (float) X, 151f);
        var reader = OmrScoreInterpreter.class.getDeclaredMethod("disconnectedHollowLedgerWing",
                byte[].class, int.class, int.class, headType, float.class, List.class);
        reader.setAccessible(true);
        return (boolean) reader.invoke(null, ink, WIDTH, HEIGHT, head, (float) GAP, List.of(178f, 161f, 148f));
    }

    @Test
    public void countsFromTheNearestLedgerPastTheLeftShaft() throws Exception {
        byte[] source = ink(151);
        for (int y = 151; y <= 277; y++) source[y * WIDTH + X - 11] = 0;
        assertTrue(Float.isFinite(stemCrossedSpace(source)));
        line(source, 178, (byte) 255);
        assertFalse(Float.isFinite(stemCrossedSpace(source)));
    }

    @Test
    public void outerLedgerAtItsOwnPhaseRetainsTheLinePosition() throws Exception {
        byte[] source = ink(151);
        for (int y = 151; y <= 277; y++) source[y * WIDTH + X - 11] = 0;
        line(source, 144, (byte) 0);
        assertFalse(Float.isFinite(stemCrossedSpace(source)));
    }

    @Test
    public void detachedFlanksDoNotSupplyAnOuterLedger() throws Exception {
        byte[] source = ink(204, new int[] {229, 213});
        for (int y = 201; y <= 204; y++) {
            Arrays.fill(source, y * WIDTH + X - 17, y * WIDTH + X - 13, (byte) 0);
            Arrays.fill(source, y * WIDTH + X + 15, y * WIDTH + X + 19, (byte) 0);
        }
        assertEquals(13, read(source, source.clone(), 204, 14));
        byte[] joined = source.clone(), incomplete = source.clone();
        for (int y = 201; y <= 204; y++)
            Arrays.fill(joined, y * WIDTH + X - 16, y * WIDTH + X + 18, (byte) 0);
        line(incomplete, 229, (byte) 255);
        assertTrue(hasDetachedLedgerWings(source));
        assertFalse(hasDetachedLedgerWings(joined));
        assertFalse(hasDetachedLedgerWings(incomplete));
    }

    private static boolean hasDetachedLedgerWings(byte[] source) throws Exception {
        var headType = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var constructor = headType.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        constructor.setAccessible(true);
        var head = constructor.newInstance(260, X - 11, X + 11, 196, 212, (float) X, 204f);
        var reader = OmrScoreInterpreter.class.getDeclaredMethod("detachedOuterLedgerWings",
                byte[].class, int.class, int.class, headType, float.class, List.class);
        reader.setAccessible(true);
        return (boolean) reader.invoke(null, source, WIDTH, HEIGHT, head, (float) GAP,
                List.of(229f, 213f, 202f));
    }

    private static float stemCrossedSpace(byte[] source) throws Exception {
        var headType = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var constructor = headType.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        constructor.setAccessible(true);
        var head = constructor.newInstance(220, X - 11, X + 11, 143, 159, (float) X, 151f);
        var reader = OmrScoreInterpreter.class.getDeclaredMethod("stemCrossedUpperSpaceIn",
                byte[].class, int.class, int.class, headType, float.class, float.class, int.class, int.class);
        reader.setAccessible(true);
        return (float) reader.invoke(null, source, WIDTH, HEIGHT, head, (float) FLOOR, (float) GAP, 20, X - 11);
    }

    private static byte[] ink(int centerY) {
        return ink(centerY, LEDGERS);
    }

    private static byte[] ink(int centerY, int[] ledgers) {
        byte[] plane = new byte[WIDTH * HEIGHT];
        Arrays.fill(plane, (byte) 255);
        for (int y : ledgers) line(plane, y, (byte) 0);
        for (int y = FLOOR - 4 * GAP; y <= FLOOR; y += GAP)
            Arrays.fill(plane, y * WIDTH + 25, y * WIDTH + WIDTH - 25, (byte) 0);
        for (int dy = -8; dy <= 8; dy++)
            for (int dx = -11; dx <= 11; dx++)
                if (dx * dx / 121.0 + (dy + .30 * dx) * (dy + .30 * dx) / 54.0 <= 1)
                    plane[(centerY + dy) * WIDTH + X + dx] = 0;
        return plane;
    }

    private static void line(byte[] plane, int y, byte value) {
        for (int row = y - 1; row <= y + 1; row++)
            Arrays.fill(plane, row * WIDTH + X - 16, row * WIDTH + X + 17, value);
    }

    private static int read(byte[] original, byte[] normalized, int y) throws Exception {
        return read(original, normalized, y, 20);
    }

    private static int read(byte[] original, byte[] normalized, int y, int step) throws Exception {
        var headType = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var constructor = headType.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        constructor.setAccessible(true);
        var head = constructor.newInstance(260, X - 11, X + 11, y - 8, y + 8, (float) X, (float) y);
        var detector = OmrScoreInterpreter.class.getDeclaredMethod("compressedUpperLedgerPitch",
                byte[].class, byte[].class, int.class, int.class, headType, float.class, float.class,
                int.class, boolean.class);
        detector.setAccessible(true);
        return (int) detector.invoke(null, normalized, original, WIDTH, HEIGHT, head, (float) FLOOR,
                (float) GAP, step, true);
    }
}
