// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    @Test
    public void naturalKeepsItsPitchWhenTheJoinedSignReachesTheNotehead() throws Exception {
        checkNaturalWithHeadAndStaff(true, true, true);
    }

    @Test
    public void aGapBetweenTheHeadAndAnUnownedIslandRetainsTheJoinedReading() throws Exception {
        checkNaturalWithHeadAndStaff(false, true, true);
    }

    @Test
    public void aShortInkIslandCannotSupplyMissingStaffOwnership() throws Exception {
        checkNaturalWithHeadAndStaff(true, false, true);
    }

    @Test
    public void aMissingNaturalConnectorCannotOverrideAJoinedReading() throws Exception {
        checkNaturalWithHeadAndStaff(true, true, false);
    }

    private void checkNaturalWithHeadAndStaff(
            boolean touchesHead, boolean intactStaff, boolean intactNatural) throws Exception {
        rect(10, 39, 80, 2, (byte) 0);
        rect(25, 30, 12, 4, OmrMeasurePostProcessor.CLEF_OR_KEY);
        rect(35, 30, 2, 29, OmrMeasurePostProcessor.CLEF_OR_KEY);
        rect(10, 53, 80, 1, OmrMeasurePostProcessor.STAFF);
        rect(35, 53, 2, 1, OmrMeasurePostProcessor.CLEF_OR_KEY);
        rect(30, 53, 2, 1, OmrMeasurePostProcessor.SYMBOL);
        rect(45, 53, 2, 1, OmrMeasurePostProcessor.SYMBOL);
        rect(40, 37, 5, 3, OmrMeasurePostProcessor.SYMBOL);
        rect(45, 34, 15, 10, OmrMeasurePostProcessor.NOTEHEAD);
        if (!touchesHead)
            for (int y = 37; y < 40; y++) gray[y * WIDTH + 44] = (byte) 255;
        if (!intactStaff)
            for (int x = 47; x < 61; x++) gray[53 * WIDTH + x] = (byte) 255;
        if (!intactNatural)
            for (int y = 44; y < 48; y++)
                for (int x = 27; x < 35; x++) gray[y * WIDTH + x] = (byte) 255;

        var omr = OmrScoreInterpreter.class;
        Class<?> component = Class.forName(omr.getName() + "$Component");
        Class<?> candidate = Class.forName(omr.getName() + "$AccidentalCandidate");
        var shape =
                component.getDeclaredConstructor(
                        int.class, int.class, int.class, int.class, int.class,
                        float.class, float.class);
        var role = candidate.getDeclaredConstructor(component, byte.class);
        shape.setAccessible(true);
        role.setAccessible(true);
        int coreArea = 0;
        for (int y = 18; y < 59; y++)
            for (int x = 25; x < 37; x++)
                if (labels[y * WIDTH + x] == OmrMeasurePostProcessor.CLEF_OR_KEY) coreArea++;
        var natural = shape.newInstance(coreArea, 25, 36, 18, 58, 30.5f, 38.5f);
        var headIsland = shape.newInstance(15, 40, 44, 37, 39, 42f, 38f);
        var innerRule = shape.newInstance(2, 30, 31, 53, 53, 30.5f, 53f);
        var outerRule = shape.newInstance(2, 45, 46, 53, 53, 45.5f, 53f);
        var joined = shape.newInstance(coreArea + 19, 25, 46, 18, 58, 32f, 39f);
        var owner = shape.newInstance(150, 45, 59, 34, 43, 52f, 38.5f);
        var source =
                omr.getDeclaredMethod(
                        "joinsNaturalToHeadAndStaff",
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        List.class,
                        component,
                        float.class,
                        component,
                        List.class);
        source.setAccessible(true);
        assertEquals(
                touchesHead && intactStaff && intactNatural,
                source.invoke(
                        null,
                        labels,
                        gray,
                        WIDTH,
                        HEIGHT,
                        List.of(role.newInstance(natural, OmrMeasurePostProcessor.CLEF_OR_KEY)),
                        owner,
                        GAP,
                        joined,
                        List.of(natural, headIsland, innerRule, outerRule)));
    }


    @Test
    public void sameBoundsStaffJoinKeepsTheOriginalNaturalOwner() throws Exception {
        var omr = OmrScoreInterpreter.class;
        Class<?> component = Class.forName(omr.getName() + "$Component");
        Class<?> candidate = Class.forName(omr.getName() + "$AccidentalCandidate");
        var shape =
                component.getDeclaredConstructor(
                        int.class, int.class, int.class, int.class, int.class,
                        float.class, float.class);
        var role = candidate.getDeclaredConstructor(component, byte.class);
        shape.setAccessible(true);
        role.setAccessible(true);
        var glyph = shape.newInstance(152, 25, 36, 18, 58, 30.5f, 38.5f);
        var staffIsland = shape.newInstance(2, 30, 31, 39, 39, 30.5f, 39f);
        var joined = shape.newInstance(154, 25, 36, 18, 58, 30.5f, 38.5f);
        var originalRole = role.newInstance(glyph, OmrMeasurePostProcessor.CLEF_OR_KEY);
        var joinedRole = role.newInstance(joined, OmrMeasurePostProcessor.CLEF_OR_KEY);
        var source =
                omr.getDeclaredMethod(
                        "naturalBeforeSameBoundsStaffFragment",
                        byte[].class,
                        byte[].class,
                        int.class,
                        candidate,
                        candidate,
                        float.class,
                        Map.class);
        source.setAccessible(true);
        var lineage = Map.of(joined, List.of(staffIsland, glyph));
        assertSame(
                originalRole,
                source.invoke(null, labels, gray, WIDTH, joinedRole, originalRole, GAP, lineage));
        assertSame(
                originalRole,
                source.invoke(null, labels, gray, WIDTH, originalRole, joinedRole, GAP, lineage));
        for (int x = 10; x < 25; x++) gray[39 * WIDTH + x] = (byte) 255;
        assertSame(
                null,
                source.invoke(null, labels, gray, WIDTH, originalRole, joinedRole, GAP, lineage));
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
