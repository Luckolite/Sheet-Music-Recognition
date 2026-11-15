// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class StaffJoinedNaturalSourceTest {
    private static final int WIDTH = 100, HEIGHT = 100;
    private static final float GAP = 14;
    private final byte[] labels = new byte[WIDTH * HEIGHT];
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    public StaffJoinedNaturalSourceTest() {
        Arrays.fill(gray, (byte) 255);
        rect(10, 39, 80, 2, OmrMeasurePostProcessor.STAFF);
        rect(25, 18, 2, 30, OmrMeasurePostProcessor.CLEF_OR_KEY);
        rect(35, 30, 2, 29, OmrMeasurePostProcessor.CLEF_OR_KEY);
        rect(25, 30, 12, 4, OmrMeasurePostProcessor.CLEF_OR_KEY);
        rect(25, 44, 12, 4, OmrMeasurePostProcessor.CLEF_OR_KEY);
        rect(44, 39, 2, 2, OmrMeasurePostProcessor.SYMBOL);
    }

    @Test
    public void completeNaturalKeepsItsOwnSourceWhenAStaffRuleExtendsTheJoin() throws Exception {
        check(38, true);
    }

    @Test
    public void inheritedNaturalCannotClaimTheOpposedHeadsNatural() throws Exception {
        check(24, false);
    }

    @Test
    public void missingStaffBridgeCannotReleaseTheOriginalOwner() throws Exception {
        rect(37, 39, 7, 2, (byte) 0);
        check(38, false);
    }

    @Test
    public void missingLowerConnectorCannotReleaseTheOriginalOwner() throws Exception {
        for (int y = 42; y <= 58; y++)
            for (int x = 27; x <= 36; x++) gray[y * WIDTH + x] = (byte) 255;
        check(38, false);
    }

    private void check(int headY, boolean retainsOriginal) throws Exception {
        var omr = OmrScoreInterpreter.class;
        Class<?> component = Class.forName(omr.getName() + "$Component");
        Class<?> candidate = Class.forName(omr.getName() + "$AccidentalCandidate");
        var shape =
                component.getDeclaredConstructor(
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        float.class,
                        float.class);
        var role = candidate.getDeclaredConstructor(component, byte.class);
        shape.setAccessible(true);
        role.setAccessible(true);
        var originalInk = shape.newInstance(136, 25, 36, 18, 58, 30.5f, 38.5f);
        var staffIsland = shape.newInstance(4, 44, 45, 39, 40, 44.5f, 39.5f);
        var joinedInk = shape.newInstance(140, 25, 45, 18, 58, 30.8f, 38.5f);
        var original = role.newInstance(originalInk, OmrMeasurePostProcessor.CLEF_OR_KEY);
        var joined = role.newInstance(joinedInk, (byte) 0);
        var owner = shape.newInstance(100, 43, 60, headY - 6, headY + 6, 51f, (float) headY);
        var source =
                omr.getDeclaredMethod(
                        "originalNaturalBeforeStaffFragment",
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        List.class,
                        component,
                        float.class,
                        candidate,
                        List.class);
        source.setAccessible(true);
        assertSame(
                retainsOriginal ? original : joined,
                source.invoke(
                        null,
                        labels,
                        gray,
                        WIDTH,
                        HEIGHT,
                        List.of(original, joined),
                        owner,
                        GAP,
                        joined,
                        List.of(staffIsland, originalInk)));
    }

    private void rect(int x, int y, int w, int h, byte label) {
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                labels[yy * WIDTH + xx] = label;
                gray[yy * WIDTH + xx] = 0;
            }
        }
    }
}
