// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Generated beam cores with a narrow scanning fringe. */
public class IsolatedBeamProbeTest {
    private int count(int halfWidth) throws Exception {
        byte[] gray = new byte[300 * 180], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int y = 45; y <= 50; y++) for (int x = 100; x <= 200; x++) gray[y * 300 + x] = 0;
        for (int y = 57; y <= 62; y++)
            for (int x = 150 - halfWidth; x <= 150 + halfWidth; x++) gray[y * 300 + x] = 0;
        var staff = Class.forName(OmrScoreInterpreter.class.getName() + "$Staff");
        var ctor = staff.getDeclaredConstructor(float.class, float.class, float.class);
        ctor.setAccessible(true);
        var method =
                OmrScoreInterpreter.class.getDeclaredMethod(
                        "supportedBeamBands",
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        staff,
                        int.class,
                        boolean.class);
        method.setAccessible(true);
        return (int)
                method.invoke(
                        null,
                        gray,
                        labels,
                        300,
                        180,
                        150,
                        40,
                        65,
                        ctor.newInstance(80f, 144f, 16f),
                        150,
                        false);
    }

    @Test
    public void longSlopedBeamRequiresTwoFilledHeadsAndAnUnbrokenCore() throws Exception {
        int width = 180, height = 160, gap = 16;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int x = 61; x <= 109; x++) {
            int bottom = 114 - Math.round((x - 61) * .25f);
            for (int y = bottom - 8; y <= bottom; y++) gray[y * width + x] = 0;
        }
        for (int y = 53; y <= 114; y++) gray[y * width + 61] = 0;
        for (int y = 37; y <= 102; y++) gray[y * width + 109] = 0;
        for (int y = -7; y <= 7; y++)
            for (int x = 0; x <= 19; x++) {
                gray[(53 + y) * width + 61 + x] = 0;
                gray[(37 + y) * width + 109 + x] = 0;
            }
        var decoder = OmrScoreInterpreter.class;
        var head = Class.forName(decoder.getName() + "$Component");
        var constructor = head.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        constructor.setAccessible(true);
        Object left = constructor.newInstance(300, 61, 80, 46, 60, 70f, 53f);
        Object right = constructor.newInstance(300, 109, 128, 30, 44, 118f, 37f);
        var staff = Class.forName(decoder.getName() + "$Staff");
        var frame = staff.getDeclaredConstructor(float.class, float.class, float.class);
        frame.setAccessible(true);
        var proof = decoder.getDeclaredMethod("longSameStaffBeamEndpoint", byte[].class,
                int.class, int.class, head, staff, java.util.List.class, MeasureRegion.class);
        proof.setAccessible(true);
        Object[] args = {gray, width, height, right, frame.newInstance(53f, 117f, (float) gap),
                java.util.List.of(left, right), new MeasureRegion(0, 1, 0, 1)};
        assertEquals(true, proof.invoke(null, args));

        Object fragment = constructor.newInstance(100, 97, 110, 96, 104, 105f, 99f);
        var classify = decoder.getDeclaredMethod("ownedSlopedBeamFragments", byte[].class,
                int.class, int.class, java.util.List.class, java.util.List.class);
        classify.setAccessible(true);
        Object[] candidates = {gray, width, height, java.util.List.of(left, right, fragment),
                java.util.List.of(args[4])};
        assertEquals(java.util.List.of(fragment), classify.invoke(null, candidates));

        for (int y = 96; y <= 110; y++) gray[y * width + 87] = (byte) 255;
        assertEquals(java.util.List.of(), classify.invoke(null, candidates));
        assertEquals(false, proof.invoke(null, args));
    }

    @Test
    public void isolatedExtraBandDoesNotShortenTheNote() throws Exception {
        assertEquals(1, count(1));
    }

    @Test
    public void broaderSecondaryBeamIsPreserved() throws Exception {
        assertEquals(2, count(10));
    }

    @Test
    public void narrowButCorroboratedSecondaryBeamIsPreserved() throws Exception {
        assertEquals(2, count(2));
    }
}
