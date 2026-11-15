// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public final class PrintedSlurChordShaftTest {
    private static final int WIDTH = 360;
    private static final int HEIGHT = 240;
    private static final float GAP = 16;
    private static final PrintedSlurOwners.GeometryHead TOP =
            new PrintedSlurOwners.GeometryHead(0, 200, 90, 0, 1);
    private static final PrintedSlurOwners.GeometryHead LOWER =
            new PrintedSlurOwners.GeometryHead(1, 200, 146, 0, 1);

    @Test
    public void continuousChordShaftKeepsAnOutsideSlurOnTheOuterHead() throws Exception {
        var gray = drawnChord();
        assertTrue(proved(gray, LOWER));
        assertFalse(proved(gray, new PrintedSlurOwners.GeometryHead(1, 206, 146, 0, 1)));
        for (int y = 111; y < 125; y++) {
            Arrays.fill(gray, y * WIDTH + 187, y * WIDTH + 194, (byte) 255);
        }
        assertFalse(proved(gray, LOWER));
    }

    @Test
    public void rejectsAContourAlongParallelBeamsWithoutRejectingAnIsolatedStroke() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        var points = new ArrayList<PrintedSlurContours.Point>();
        for (int x = 80; x <= 180; x++) {
            points.add(new PrintedSlurContours.Point(x, 143.5f));
            for (int y = 141; y <= 146; y++) gray[y * WIDTH + x] = 0;
            for (int y = 151; y <= 156; y++) gray[y * WIDTH + x] = 0;
        }
        var bow = new PrintedSlurContours.Bow(points, false, .18f, .09f);
        assertTrue(PrintedSlurOwners.followsParallelBeam(bow, gray, WIDTH, HEIGHT, GAP));
        for (int y = 151; y <= 156; y++) {
            Arrays.fill(gray, y * WIDTH, (y + 1) * WIDTH, (byte) 255);
        }
        assertFalse(PrintedSlurOwners.followsParallelBeam(bow, gray, WIDTH, HEIGHT, GAP));
    }

    private static byte[] drawnChord() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 90; y <= 169; y++) for (int x = 190; x <= 192; x++) gray[y * WIDTH + x] = 0;
        for (int centerY : new int[] {90, 146})
            for (int y = centerY - 7; y <= centerY + 7; y++)
                for (int x = 190; x <= 211; x++)
                    if (Math.pow((x - 200) / 10d, 2) + Math.pow((y - centerY) / 7d, 2) <= 1)
                        gray[y * WIDTH + x] = 0;
        return gray;
    }

    private static boolean proved(byte[] gray, PrintedSlurOwners.GeometryHead lower)
            throws Exception {
        var proof =
                PrintedSlurOwners.class.getDeclaredMethod(
                        "continuousChordShaft",
                        byte[].class,
                        int.class,
                        int.class,
                        PrintedSlurOwners.Location.class,
                        PrintedSlurOwners.Location.class,
                        float.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, gray, WIDTH, HEIGHT, TOP, lower, GAP);
    }
}
