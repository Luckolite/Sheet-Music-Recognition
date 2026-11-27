// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.lang.reflect.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original raster fixtures for attached flag roots, not commercial crops. */
public class DetachedFlagStemEvidenceTest {
    private static final int W = 100, H = 140;

    private int flags(int roots, boolean rawStem, int stemTop) throws Exception {
        return flags(roots, rawStem, stemTop, false);
    }

    private int flags(int roots, boolean rawStem, int stemTop, boolean oppositeIsland)
            throws Exception {
        byte[] gray = new byte[W * H], labels = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        box(gray, 40, 86, 52, 94, 0);
        if (rawStem) box(gray, 51, stemTop, 52, 90, 0);
        // Fragmented semantic evidence is shorter than one gap; raw attachment is complete.
        box(labels, 51, 70, 52, 82, 1);
        if (oppositeIsland) box(labels, 40, 100, 40, 125, 1);
        for (int i = 0; i < roots; i++)
            box(gray, 53, stemTop + i * 14, 61, stemTop + i * 14 + 3, 0);
        Class<?> head = Class.forName("io.github.luckolite.interpreter.OmrScoreInterpreter$Component");
        var constructor =
                head.getDeclaredConstructor(
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        float.class,
                        float.class);
        constructor.setAccessible(true);
        Object component = constructor.newInstance(100, 40, 52, 86, 94, 46f, 90f);
        var method =
                OmrScoreInterpreter.class.getDeclaredMethod(
                        "rawDetachedFlags",
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        head,
                        float.class);
        method.setAccessible(true);
        return (int) method.invoke(null, gray, labels, W, H, component, 14f);
    }

    private void box(byte[] pixels, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) pixels[y * W + x] = (byte) value;
    }

    @Test
    public void rawAttachedStemRetainsTwoRootsDespiteFragmentedSemanticStem() throws Exception {
        assertEquals(2, flags(2, true, 40));
    }

    @Test
    public void oneRootDoesNotBecomeTwo() throws Exception {
        assertEquals(1, flags(1, true, 40));
    }

    @Test
    public void unattachedAnnotationDoesNotSupplyAStem() throws Exception {
        assertEquals(0, flags(2, false, 40));
    }

    @Test
    public void longChordShaftIsOutsideTheDetachedFlagWindow() throws Exception {
        assertEquals(0, flags(2, true, 10));
    }

    @Test
    public void attachedShaftOverridesAnUnconnectedOppositeSemanticIsland() throws Exception {
        assertEquals(2, flags(2, true, 40, true));
    }

    @Test
    public void downwardAttachedStemAlsoKeepsBothFlagRoots() throws Exception {
        byte[] gray = new byte[W * H], labels = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        box(gray, 40, 36, 52, 44, 0);
        box(gray, 39, 40, 40, 90, 0);
        box(labels, 39, 47, 40, 59, 1);
        box(gray, 41, 87, 49, 90, 0);
        box(gray, 41, 73, 49, 76, 0);
        Class<?> head = Class.forName("io.github.luckolite.interpreter.OmrScoreInterpreter$Component");
        var constructor =
                head.getDeclaredConstructor(
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        float.class,
                        float.class);
        constructor.setAccessible(true);
        Object component = constructor.newInstance(100, 40, 52, 36, 44, 46f, 40f);
        var method =
                OmrScoreInterpreter.class.getDeclaredMethod(
                        "rawDetachedFlags",
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        head,
                        float.class);
        method.setAccessible(true);
        assertEquals(2, (int) method.invoke(null, gray, labels, W, H, component, 14f));
    }
    @Test
    public void partiallySampledStaffRuleDoesNotBecomeADetachedFlag() throws Exception {
        int width = 180, height = 140, shaft = 80, tip = 91;
        byte[] gray = new byte[width * height], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int y = 37; y <= 45; y++)
            Arrays.fill(gray, y * width + 80, y * width + 93, (byte) 0);
        for (int y = 41; y <= tip; y++) {
            gray[y * width + shaft] = 0;
            gray[y * width + shaft - 1] = 0;
            if (y >= 48 && y <= 59) labels[y * width + shaft] = 1;
        }
        // Near-shaft antialiasing spills above a long rule whose outer strips have a different phase.
        for (int x = 4; x < width - 4; x++) {
            gray[89 * width + x] = 0;
            if (Math.abs(x - shaft) > 27) gray[88 * width + x] = 0;
        }
        for (int y = 87; y <= 88; y++)
            Arrays.fill(gray, y * width + 83, y * width + 91, (byte) 0);
        Class<?> head = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var constructor = head.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object component = constructor.newInstance(110, 80, 92, 37, 45, 86f, 41f);
        var method = OmrScoreInterpreter.class.getDeclaredMethod(
                "rawDetachedFlags", byte[].class, byte[].class, int.class, int.class, head, float.class);
        method.setAccessible(true);
        assertEquals(0, (int) method.invoke(null, gray, labels, width, height, component, 14f));
    }
}
