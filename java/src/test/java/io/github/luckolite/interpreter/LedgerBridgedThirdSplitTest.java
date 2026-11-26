// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class LedgerBridgedThirdSplitTest {
    private static final int WIDTH = 440;
    private static final int HEIGHT = 370;
    private static final float GAP = 16;

    @Test
    public void bothPaperPlanesMustConfirmTheLedgerAndTheTwoBeamBridges() throws Exception {
        var page = new Fixture();
        assertEquals(2, page.split(page.gray).size());
        for (int[] box : new int[][] {{195, 202, 244, 267}, {239, 246, 244, 267},
                {229, 240, 179, 185}, {214, 229, 190, 199}}) {
            byte[] changed = page.gray.clone();
            fill(changed, box[0], box[1], box[2], box[3], (byte) 255);
            assertTrue(page.split(changed).isEmpty());
        }
    }

    @Test
    public void remoteThirdKeepsBothHeadsAcrossWideBeamSpacing() throws Exception {
        var page = new Fixture(true);
        assertEquals(2, page.wideSplit(page.gray).size());
        for (int[] range : new int[][] {{192, 205}, {262, 276}}) {
            byte[] changed = page.gray.clone();
            fill(changed, range[0], range[1], 244, 267, (byte) 255);
            assertTrue(page.wideSplit(changed).isEmpty());
        }
    }

    private static final class Fixture {
        final byte[] gray = new byte[WIDTH * HEIGHT];
        final byte[] labels = new byte[WIDTH * HEIGHT];

        Fixture() {
            this(false);
        }

        Fixture(boolean wide) {
            Arrays.fill(gray, (byte) 255);
            for (int y = 230; y <= 294; y += (int) GAP) {
                fill(gray, 30, 410, y, y + 1, (byte) 0);
                fill(labels, 30, 410, y, y + 1, (byte) 4);
            }
            third(wide ? 170 : 180, 182);
            third(wide ? 230 : 220, 174);
            third(wide ? 300 : 260, 166);
            fill(gray, wide ? 159 : 169, wide ? 292 : 252, 245, 252, (byte) 0);
            fill(gray, wide ? 159 : 169, wide ? 292 : 252, 258, 266, (byte) 0);
        }

        private void third(int x, int y) {
            fill(gray, x - 11, x - 8, y + 2, 266, (byte) 0);
            for (int row : new int[] {y, y + 16}) {
                for (int yy = row - 9; yy <= row + 9; yy++) {
                    for (int xx = x - 11; xx <= x + 11; xx++) {
                        if (Math.pow(Math.abs(xx - x) / 11.4, 3)
                                        + Math.pow(Math.abs(yy - row) / 9.6, 3) <= 1) {
                            labels[yy * WIDTH + xx] = 2;
                            gray[yy * WIDTH + xx] = 0;
                        }
                    }
                }
            }
            fill(gray, x - 15, x + 16, y + 8, y + 9, (byte) 0);
        }

        List<?> split(byte[] raw) throws ReflectiveOperationException {
            return split(raw, false);
        }

        List<?> wideSplit(byte[] raw) throws ReflectiveOperationException {
            return split(raw, true);
        }

        private List<?> split(byte[] raw, boolean wide) throws ReflectiveOperationException {
            Class<?> omr = OmrScoreInterpreter.class;
            Class<?> component = Class.forName(omr.getName() + "$Component");
            Class<?> staffType = Class.forName(omr.getName() + "$Staff");
            var makeStaff = staffType.getDeclaredConstructor(float.class, float.class, float.class);
            makeStaff.setAccessible(true);
            Object staff = makeStaff.newInstance(230f, 294f, GAP);
            var find = omr.getDeclaredMethod("findComponents", byte[].class, int.class, int.class,
                    byte.class);
            find.setAccessible(true);
            var heads = (List<?>) find.invoke(null, labels, WIDTH, HEIGHT, (byte) 2);
            assertEquals(3, heads.size());
            var centerX = component.getDeclaredMethod("centerX");
            centerX.setAccessible(true);
            Object parent = null;
            for (var head : heads) {
                if (Math.abs((float) centerX.invoke(head) - (wide ? 230 : 220)) < 2) parent = head;
            }
            if (wide) {
                var proof = omr.getDeclaredMethod("splitWidelyBeamedLedgerThird",
                        byte[].class, byte[].class, byte[].class, int.class, int.class, component,
                        List.class, List.class, List.class, ExactComponentMembers.Collector.class);
                proof.setAccessible(true);
                return (List<?>) proof.invoke(null, labels, gray, raw, WIDTH, HEIGHT, parent,
                        List.of(staff), heads, List.of(new MeasureRegion(.08f, .92f, .57f, .90f)), null);
            }
            var proof = omr.getDeclaredMethod("splitLedgerBridgedBeamedThird",
                    byte[].class, byte[].class, byte[].class, int.class, int.class, component,
                    List.class, List.class, ExactComponentMembers.Collector.class);
            proof.setAccessible(true);
            return (List<?>) proof.invoke(null, labels, gray, raw, WIDTH, HEIGHT, parent,
                    List.of(staff), heads, null);
        }
    }

    private static void fill(byte[] plane, int left, int right, int top, int bottom, byte tone) {
        for (int y = top; y < bottom; y++)
            Arrays.fill(plane, y * WIDTH + left, y * WIDTH + right, tone);
    }
}
