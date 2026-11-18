// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public final class BeamedStaccatoOwnershipTest {
    private static final int WIDTH = 240, HEIGHT = 220;
    private static final float GAP = 16f;
    private static final Class<?> OWNER = OmrScoreInterpreter.class;

    @Test
    public void thickPortatoDashKeepsItsOwnDetachedDot() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        int x = 105, y = 126;
        for (int row = 94; row <= 99; row++)
            Arrays.fill(gray, row * WIDTH + x - 9, row * WIDTH + x + 10, (byte) 0);
        for (int row = 106; row <= 110; row++)
            Arrays.fill(gray, row * WIDTH + x - 2, row * WIDTH + x + 3, (byte) 0);
        assertTrue(OmrScoreInterpreter.ownsAbovePortato(gray, WIDTH, HEIGHT, x, y, GAP));
        for (int row = 94; row <= 99; row++)
            Arrays.fill(gray, row * WIDTH + x - 9, row * WIDTH + x + 10, (byte) 255);
        assertFalse(OmrScoreInterpreter.ownsAbovePortato(gray, WIDTH, HEIGHT, x, y, GAP));
    }

    @Test
    public void aWideRoundMarkBelongsToTheNextHigherHead() throws Exception {
        var shape = component();
        var left = head(shape, 100, 150);
        var right = head(shape, 134, 134);
        var count =
                newMethod(
                        "countAugmentationDots",
                        List.class,
                        shape.getDeclaringClass(),
                        float.class,
                        byte[].class,
                        int.class,
                        int.class,
                        boolean.class,
                        List.class,
                        List.class);
        var page = beams();
        dot(page, 100, 174);
        dot(page, 134, 159);
        assertEquals(0, dotCount(count, page, left, right));

        var isolated = page.clone();
        ink(isolated, 96, 170, 104, 178, 255);
        assertEquals(1, dotCount(count, isolated, left, right));

        ink(page, 130, 155, 138, 163, 255);
        dot(page, 119, 151);
        assertEquals(1, dotCount(count, page, left, right));
    }

    @Test
    public void closeDyadsRequireACorroboratingStaccatoOnTheSameBeams() throws Exception {
        var shape = component();
        var left = head(shape, 100, 150);
        var neighbors =
                List.of(
                        left,
                        head(shape, 100, 134),
                        head(shape, 130, 134),
                        head(shape, 130, 118),
                        head(shape, 160, 142),
                        head(shape, 160, 126));
        var count =
                newMethod(
                        "countAugmentationDots",
                        List.class,
                        shape.getDeclaringClass(),
                        float.class,
                        byte[].class,
                        int.class,
                        int.class,
                        boolean.class,
                        List.class,
                        List.class);
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        ink(page, 110, 74, 111, 150, 0);
        ink(page, 140, 74, 141, 134, 0);
        ink(page, 170, 74, 171, 142, 0);
        ink(page, 110, 74, 171, 83, 0);
        ink(page, 110, 86, 171, 95, 0);
        dot(page, 130, 151);
        dot(page, 160, 159);
        assertEquals(0, dotCount(count, page, left, neighbors));

        var noCorroboration = page.clone();
        ink(noCorroboration, 156, 154, 165, 164, 255);
        assertEquals(1, dotCount(count, noCorroboration, left, neighbors));

        var severed = page.clone();
        ink(severed, 150, 84, 153, 96, 255);
        assertEquals(1, dotCount(count, severed, left, neighbors));

        ink(page, 126, 146, 135, 156, 255);
        dot(page, 119, 151);
        assertEquals(1, dotCount(count, page, left, neighbors));
    }

    @Test
    public void portatoAfterABeamBreakHasItsOwnOctaveDyad() throws Exception {
        var shape = component();
        var previous = head(shape, 100, 102);
        var next = head(shape, 130, 110);
        var nextLower = head(shape, 130, 166);
        var neighbors =
                List.of(
                        head(shape, 70, 110),
                        head(shape, 70, 166),
                        previous,
                        head(shape, 100, 158),
                        next,
                        nextLower,
                        head(shape, 160, 102),
                        head(shape, 160, 158));
        var rounded = shape.newInstance(24, 128, 132, 90, 94, 130f, 92f);
        var owns =
                newMethod(
                        "nextOctaveOwnsPortatoDot",
                        byte[].class,
                        int.class,
                        int.class,
                        shape.getDeclaringClass(),
                        shape.getDeclaringClass(),
                        List.class,
                        float.class);

        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        for (int i = 0; i < 4; i++) {
            int x = 70 + 30 * i;
            int y = i == 0 || i == 2 ? 110 : 102;
            ink(page, x - 11, y, x - 10, 208, 0);
            dot(page, x, y - 18);
            ink(page, x - 9, y - 30, x + 9, y - 27, 0);
        }
        for (int x : new int[] {59, 119}) {
            ink(page, x, 189, x + 31, 197, 0);
            ink(page, x, 200, x + 31, 208, 0);
        }
        assertEquals(
                List.of(next, nextLower),
                owns.invoke(null, page, WIDTH, HEIGHT, previous, rounded, neighbors, GAP));

        var noNextDash = page.clone();
        ink(noNextDash, 117, 77, 143, 85, 255);
        assertEquals(
                List.of(),
                owns.invoke(null, noNextDash, WIDTH, HEIGHT, previous, rounded, neighbors, GAP));

        var cutRightBeam = page.clone();
        ink(cutRightBeam, 132, 187, 137, 198, 255);
        assertEquals(
                List.of(),
                owns.invoke(null, cutRightBeam, WIDTH, HEIGHT, previous, rounded, neighbors, GAP));
    }

    @Test
    public void detachedMarkDoesNotOwnAThirdRail() throws Exception {
        var shape = component();
        var left = head(shape, 100, 150);
        var right = head(shape, 134, 134);
        var proof =
                newMethod(
                        "detachedMarkAboveDoubleBeam",
                        byte[].class,
                        int.class,
                        int.class,
                        shape.getDeclaringClass(),
                        List.class,
                        float.class);
        var page = beams();
        ink(page, 133, 57, 145, 72, 0);
        assertTrue(closedDoubleBeam(proof, page, left, right));

        var noAnnotation = page.clone();
        ink(noAnnotation, 131, 54, 147, 72, 255);
        assertFalse(closedDoubleBeam(proof, noAnnotation, left, right));

        ink(page, 110, 100, 144, 108, 0);
        assertFalse(closedDoubleBeam(proof, page, left, right));
    }

    @Test
    public void theFollowingPortatoNeedsBothDashesAndTheSharedSingleBeam() throws Exception {
        var shape = component();
        var left = head(shape, 100, 150);
        var right = head(shape, 132, 134);
        var count = newMethod(
                "countAugmentationDots", List.class, shape.getDeclaringClass(),
                float.class, byte[].class, int.class, int.class, boolean.class, List.class, List.class);
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        for (int cx : new int[] {100, 132}) {
            int cy = cx == 100 ? 150 : 134;
            for (int dy = -5; dy <= 5; dy++)
                for (int dx = -9; dx <= 9; dx++)
                    if (dx * dx / 81d + dy * dy / 25d <= 1)
                        page[(cy + dy) * WIDTH + cx + dx] = 0;
        }
        ink(page, 108, 90, 109, 150, 0);
        ink(page, 140, 80, 141, 134, 0);
        for (int x = 108; x <= 141; x++) {
            int y = 90 - Math.round((x - 108) * .3f);
            ink(page, x, y, x, y + 6, 0);
        }
        dot(page, 100, 168);
        dot(page, 132, 152);
        ink(page, 91, 181, 109, 184, 0);
        ink(page, 123, 165, 141, 168, 0);
        assertEquals(0, dotCount(count, page, left, right));

        var missingDash = page.clone();
        ink(missingDash, 123, 165, 141, 168, 255);
        assertEquals(1, dotCount(count, missingDash, left, right));

        var brokenBeam = page.clone();
        ink(brokenBeam, 120, 78, 126, 101, 255);
        assertEquals(1, dotCount(count, brokenBeam, left, right));

        dot(page, 119, 151);
        assertEquals(1, dotCount(count, page, left, right));
    }

    private static int dotCount(Method count, byte[] page, Object left, Object right)
            throws Exception {
        return dotCount(count, page, left, List.of(left, right));
    }

    private static int dotCount(Method count, byte[] page, Object left, List<?> neighbors)
            throws Exception {
        return (int)
                count.invoke(
                        null, List.of(), left, GAP, page, WIDTH, HEIGHT, false, List.of(),
                        neighbors);
    }

    private static boolean closedDoubleBeam(Method proof, byte[] page, Object left, Object right)
            throws Exception {
        return (boolean) proof.invoke(null, page, WIDTH, HEIGHT, right, List.of(left, right), GAP);
    }

    private static Constructor<?> component() throws Exception {
        var shape = Class.forName(OWNER.getName() + "$Component");
        var result =
                shape.getDeclaredConstructor(
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        float.class,
                        float.class);
        result.setAccessible(true);
        return result;
    }

    private static Object head(Constructor<?> shape, int x, int y) throws Exception {
        return shape.newInstance(306, x - 10, x + 11, y - 8, y + 8, (float) x, (float) y);
    }

    private static Method newMethod(String name, Class<?>... args) throws Exception {
        var method = OWNER.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    private static byte[] beams() {
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        ink(page, 110, 74, 111, 150, 0);
        ink(page, 144, 74, 145, 134, 0);
        ink(page, 110, 74, 145, 83, 0);
        ink(page, 110, 86, 145, 95, 0);
        return page;
    }

    private static void dot(byte[] page, int x, int y) {
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                if (dx * dx + dy * dy <= 12) page[(y + dy) * WIDTH + x + dx] = 0;
            }
        }
    }

    private static void ink(byte[] page, int left, int top, int right, int bottom, int shade) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) shade);
        }
    }
}
