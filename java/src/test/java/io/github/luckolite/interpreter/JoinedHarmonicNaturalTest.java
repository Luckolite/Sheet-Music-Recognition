// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class JoinedHarmonicNaturalTest {
    private static final int WIDTH = 192;
    private static final int HEIGHT = 176;
    private static final int GAP = 16;
    private static final int X = 110;
    private static final int Y = 80;

    @Test
    public void staggeredNaturalsRetainTheStoppedHeadOwner() throws Exception {
        var fixture = new Fixture();
        assertSame(fixture.signs, fixture.recover(fixture.gray));
    }

    @Test
    public void missingNaturalAndMissingTouchDoNotClaimAnOwner() throws Exception {
        var fixture = new Fixture();
        byte[] withoutLower = fixture.gray.clone();
        for (int y = Y - 26; y <= Y + 24; y++) {
            if (y == Y - 8 || y == Y + 8 || y == Y + 24) continue;
            Arrays.fill(withoutLower, y * WIDTH + X - 43, y * WIDTH + X - 29, (byte) 255);
        }
        assertNull(fixture.recover(withoutLower));
        byte[] withoutTouch = fixture.gray.clone();
        for (int y = Y - 33; y <= Y - 15; y++)
            Arrays.fill(withoutTouch, y * WIDTH + X - 9, y * WIDTH + X + 13, (byte) 255);
        assertNull(fixture.recover(withoutTouch));
    }

    private static final class Fixture {
        final byte[] labels = new byte[WIDTH * HEIGHT];
        final byte[] gray = new byte[WIDTH * HEIGHT];
        final Method recovery;
        final Object stop;
        final Object signs;

        Fixture() throws Exception {
            Arrays.fill(gray, (byte) 255);
            for (int line = 0; line < 5; line++)
                for (int x = 20; x < WIDTH - 10; x++) ink(x, Y - 8 + line * GAP, 4);
            natural(X - 42, Y);
            natural(X - 28, Y - 24);
            for (int y = Y - 24; y < Y + 48; y++) {
                ink(X - 11, y, 1);
                ink(X - 10, y, 1);
            }
            for (int dy = -8; dy <= 8; dy++) for (int dx = -11; dx <= 11; dx++) {
                float ellipse = dx * dx / 121f + dy * dy / 64f;
                if (ellipse <= 1) ink(X + dx, Y + dy, 2);
                float diamond = Math.abs(dx) / 11f + Math.abs(dy) / 8f;
                if (diamond >= .43f && diamond <= 1.07f) ink(X + dx, Y - 24 + dy, 5);
            }
            Class<?> owner = OmrScoreInterpreter.class;
            Class<?> component = Class.forName(owner.getName() + "$Component");
            Constructor<?> make = component.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class, float.class, float.class);
            make.setAccessible(true);
            stop = make.newInstance(273, X - 11, X + 11, Y - 8, Y + 8, (float) X, (float) Y);
            signs = make.newInstance(600, X - 42, X - 8, Y - 48, Y + 23, X - 24f, Y - 13f);
            recovery = owner.getDeclaredMethod("naturalBelowJoinedHarmonicTouch",
                    byte[].class, byte[].class, byte[].class, int.class, int.class,
                    component, float.class, List.class);
            recovery.setAccessible(true);
        }

        private void natural(int x, int y) {
            for (int k = 0; k < 3; k++) {
                for (int row = -24; row <= 13; row++) ink(x + k, y + row, 3);
                for (int row = -11; row <= 23; row++) ink(x + 10 + k, y + row, 3);
            }
            for (int col = 0; col <= 12; col++) {
                int rise = col / 4;
                for (int k = 0; k < 5; k++) {
                    ink(x + col, y - 9 - rise + k, 3);
                    ink(x + col, y + 8 - rise + k, 3);
                }
            }
        }

        private void ink(int x, int y, int label) {
            gray[y * WIDTH + x] = 0;
            labels[y * WIDTH + x] = (byte) label;
        }

        Object recover(byte[] annotation) throws Exception {
            return recovery.invoke(null, labels, gray, annotation, WIDTH, HEIGHT, stop, (float) GAP,
                    List.of(signs));
        }
    }
}
