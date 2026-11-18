// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class DividerJoinedSharpTest {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 180;
    private static final float GAP = 16;

    @Test
    public void pairBelongsToLowerHeadBeyondTheStaffDivider() throws Exception {
        byte[] raw = ink();
        assertTrue(sharp(raw, raw, false));
        assertFalse(sharp(raw, raw, true));

        byte[] noDivider = raw.clone();
        fill(noDivider, 48, 53, 107, 119, 255);
        assertFalse(sharp(raw, noDivider, false));

        byte[] missingUpright = raw.clone();
        fill(missingUpright, 55, 59, 82, 126, 255);
        assertFalse(sharp(raw, missingUpright, false));
    }

    @Test
    public void aSmallDisplacementCanReachTheSharedShaftProof() throws Exception {
        Class<?> c = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var head = c.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        head.setAccessible(true);
        Class<?> d = Class.forName(OmrScoreInterpreter.class.getName() + "$DetectedNote");
        var note = d.getDeclaredConstructor(ScoreNoteEvent.class, c, float.class);
        note.setAccessible(true);
        var low = head.newInstance(245, 74, 94, 98, 112, 84f, 105f);
        var high = head.newInstance(245, 72, 92, 58, 72, 82f, 65f);
        var next = head.newInstance(245, 91, 111, 58, 72, 101f, 65f);
        var first = new ScoreNoteEvent(1, .05f, 5, 0, 1, .5f, false, 0, 2,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0);
        var proof = OmrScoreInterpreter.class.getDeclaredMethod("sharesDividerDyadAttack", d, d);
        proof.setAccessible(true);
        var a = note.newInstance(first, low, GAP);
        assertTrue((boolean) proof.invoke(null, a, note.newInstance(first, high, GAP)));
        assertFalse((boolean) proof.invoke(null, a, note.newInstance(first, next, GAP)));
    }

    private static byte[] ink() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 72; y <= 136; y += 16) fill(gray, 12, 163, y, y + 2, 0);
        fill(gray, 49, 53, 72, 138, 0);
        fill(gray, 72, 75, 66, 150, 0);
        oval(gray, 84, 105);
        oval(gray, 82, 65);
        fill(gray, 56, 59, 82, 126, 0);
        fill(gray, 64, 67, 81, 124, 0);
        for (int x = 53; x < 71; x++) {
            int rise = Math.round((x - 53) * .30f);
            fill(gray, x, x + 1, 99 - rise, 103 - rise, 0);
            fill(gray, x, x + 1, 115 - rise, 119 - rise, 0);
        }
        return gray;
    }

    private static boolean sharp(byte[] normalized, byte[] original, boolean reverse) throws Exception {
        Class<?> c = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var head = c.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        head.setAccessible(true);
        var lower = head.newInstance(245, 74, 94, 98, 112, 84f, 105f);
        var upper = head.newInstance(245, 72, 92, 58, 72, 82f, 65f);
        Class<?> frame = Class.forName(OmrScoreInterpreter.class.getName() + "$Staff");
        var constructor = frame.getDeclaredConstructor(float.class, float.class, float.class);
        constructor.setAccessible(true);
        var staff = constructor.newInstance(72f, 136f, GAP);
        var proof = OmrScoreInterpreter.class.getDeclaredMethod("dividerSharpPitch", byte[].class,
                byte[].class, int.class, int.class, c, c, float.class, frame);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, normalized, original, WIDTH, HEIGHT,
                reverse ? upper : lower, reverse ? lower : upper, 50f, staff);
    }

    private static void oval(byte[] ink, int cx, int cy) {
        for (int y = cy - 7; y <= cy + 7; y++) {
            for (int x = cx - 10; x <= cx + 10; x++) {
                float dy = y - cy + (x - cx) * .19f;
                if ((x - cx) * (x - cx) / 100f + dy * dy / 42f <= 1) ink[y * WIDTH + x] = 0;
            }
        }
    }

    private static void fill(byte[] ink, int left, int right, int top, int bottom, int value) {
        for (int y = top; y < bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right, (byte) value);
    }
}
