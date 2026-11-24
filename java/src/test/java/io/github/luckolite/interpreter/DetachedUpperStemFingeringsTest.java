// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original separated upper ink and beam shafts, with competing and incomplete controls. */
public class DetachedUpperStemFingeringsTest {
    private static final int WIDTH = 240, HEIGHT = 260;
    private final PrintedSlurOwners.GeometryHead head =
            new PrintedSlurOwners.GeometryHead(0, 100, 190, 0, 1);

    private byte[] scene(int paperGap, boolean numeral) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 130; y <= 190; y++)
            for (int x = 107; x <= 109; x++) gray[y * WIDTH + x] = 0;
        for (int y = 187; y <= 193; y++)
            for (int x = 92; x <= 108; x++) gray[y * WIDTH + x] = 0;
        for (int y = 130; y <= 134; y++)
            for (int x = 72; x <= 165; x++) gray[y * WIDTH + x] = 0;
        if (numeral)
            for (int y = 108; y < 130 - paperGap; y++)
                for (int x = 107; x <= 109; x++) gray[y * WIDTH + x] = 0;
        return gray;
    }

    @Test
    public void completeLiteralShaftStopsBelowSeparatedUpperInk() {
        byte[] gray = scene(2, true), before = gray.clone();
        var permissive = PairedTremoloInk.stem(gray, WIDTH, HEIGHT, head.x(), head.y(), 16, 1);
        assertNotNull(permissive);
        assertEquals(108, permissive.endY());
        var physical = DetachedUpperStemFingerings.physical(gray, WIDTH, HEIGHT, head, 16);
        assertNotNull(physical);
        assertEquals(130, physical.endY());
        assertEquals(108, physical.x());
        assertArrayEquals(before, gray);
    }

    @Test
    public void ordinaryBeamTipNeedsNoReplacement() {
        assertNull(DetachedUpperStemFingerings.physical(scene(2, false), WIDTH, HEIGHT, head, 16));
    }

    @Test
    public void paperSeparatedUpperMarkDoesNotExtendPermissiveTrace() {
        assertNull(DetachedUpperStemFingerings.physical(scene(5, true), WIDTH, HEIGHT, head, 16));
    }

    @Test
    public void ordinaryDownstemTipIsAvailableOnlyToTheShapeMatchingPath() {
        byte[] upper = scene(2, false);
        byte[] lower = new byte[upper.length];
        for (int i = 0; i < upper.length; i++) lower[upper.length - 1 - i] = upper[i];
        var downstem = new PrintedSlurOwners.GeometryHead(0, WIDTH - 1 - head.x(), HEIGHT - 1 - head.y(), 0, 1);
        var trace = PairedTremoloInk.stem(lower, WIDTH, HEIGHT, downstem.x(), downstem.y(), 16, -1);
        assertNotNull(trace);
        assertEquals(-1, MeasuredStaffFingerings.physicalDownstemEnd(lower, WIDTH, HEIGHT, downstem,
                trace, 16, false, new PrintedBeamShafts.Budget(50_000)));
        assertEquals(HEIGHT - 1 - 130, MeasuredStaffFingerings.physicalDownstemEnd(
                lower, WIDTH, HEIGHT, downstem, trace, 16, true, new PrintedBeamShafts.Budget(50_000)));
    }

    @Test
    public void opposingLiteralShaftPreservesAmbiguity() {
        byte[] gray = scene(2, true);
        for (int y = 190; y <= 244; y++)
            for (int x = 91; x <= 93; x++) gray[y * WIDTH + x] = 0;
        assertNull(DetachedUpperStemFingerings.physical(gray, WIDTH, HEIGHT, head, 16));
    }
}
