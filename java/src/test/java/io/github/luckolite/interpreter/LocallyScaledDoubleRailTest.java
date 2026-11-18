// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class LocallyScaledDoubleRailTest {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 180;

    @Test
    public void enclosingStaffScaleResolvesTheBeamFragment() throws Exception {
        var sample = new Sample();
        assertEquals(List.of(sample.fragment), sample.classify());
    }

    @Test
    public void widerScaleCannotReplaceTheMissingBeamConnection() throws Exception {
        var sample = new Sample();
        for (int y = 44; y <= 68; y++) {
            Arrays.fill(sample.gray, y * WIDTH + 74, y * WIDTH + 91, (byte) 255);
        }
        assertEquals(List.of(), sample.classify());
    }

    private static final class Sample {
        final byte[] gray = new byte[WIDTH * HEIGHT];
        final Object fragment;
        final List<Object> heads;
        final Object staff;
        final Method proof;

        Sample() throws Exception {
            Arrays.fill(gray, (byte) 255);
            for (int x : new int[] {50, 102}) {
                for (int y = 102; y <= 114; y++) {
                    Arrays.fill(gray, y * WIDTH + x - 10, y * WIDTH + x + 11, (byte) 0);
                }
                for (int y = 47; y <= 108; y++) {
                    Arrays.fill(gray, y * WIDTH + x + 9, y * WIDTH + x + 12, (byte) 0);
                }
            }
            for (int y = 47; y <= 53; y++) Arrays.fill(gray, y * WIDTH + 59, y * WIDTH + 114, (byte) 0);
            for (int y = 59; y <= 65; y++) Arrays.fill(gray, y * WIDTH + 59, y * WIDTH + 114, (byte) 0);

            var decoder = OmrScoreInterpreter.class;
            var component = Class.forName(decoder.getName() + "$Component");
            Constructor<?> head = component.getDeclaredConstructor(int.class, int.class, int.class,
                    int.class, int.class, float.class, float.class);
            head.setAccessible(true);
            Object left = head.newInstance(290, 40, 61, 101, 115, 50f, 108f);
            Object right = head.newInstance(290, 92, 113, 101, 115, 102f, 108f);
            fragment = head.newInstance(197, 75, 94, 53, 67, 84f, 59.7f);
            heads = List.of(left, right, fragment);
            var staffType = Class.forName(decoder.getName() + "$Staff");
            var frame = staffType.getDeclaredConstructor(float.class, float.class, float.class);
            frame.setAccessible(true);
            staff = frame.newInstance(64f, 124f, 15f);
            proof = decoder.getDeclaredMethod("locallyScaledDoubleRailFragments", byte[].class,
                    int.class, int.class, List.class, List.class, List.class);
            proof.setAccessible(true);
        }

        Object classify() throws Exception {
            var measure = new MeasureRegion(0, 1, 18f / HEIGHT, 150.5f / HEIGHT);
            return proof.invoke(null, gray, WIDTH, HEIGHT, heads, List.of(staff), List.of(measure));
        }
    }
}
