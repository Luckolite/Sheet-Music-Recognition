// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static io.github.luckolite.interpreter.BareQuarterStemTest.*;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Original geometric controls for short hooks and stem contacts. */
public final class PrintedRhythmOwnershipTest {
    private final byte[] gray = new byte[W * H];
    private final byte[] labels = new byte[W * H];

    public PrintedRhythmOwnershipTest() {
        Arrays.fill(gray, (byte) 255);
    }

    private void ink(int x, int y, int w, int h) {
        rect(labels, gray, x, y, w, h, 1, 0);
    }

    private boolean borrowedHook(boolean joined) throws Exception {
        oval(labels, gray, 64, 107);
        oval(labels, gray, 96, 107);
        ink(73, 50, 2, 58);
        ink(105, 50, 2, 58);
        ink(73, 50, 34, 7);
        ink(73, 61, joined ? 34 : 22, 6);
        var a = head(64, 107);
        var b = head(96, 107);
        var proof = OmrScoreInterpreter.class.getDeclaredMethod("onlyNeighborOwnsBeamHook",
                byte[].class, int.class, int.class, component, List.class, float.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, gray, W, H, b, List.of(a, b), G);
    }

    @Test public void detachedHookBelongsOnlyToItsOwnStem() throws Exception {
        assertTrue(borrowedHook(false));
    }

    @Test public void completeSecondRailStillBelongsToBothStems() throws Exception {
        assertFalse(borrowedHook(true));
    }

    private boolean letterCounter(boolean closed) throws Exception {
        oval(labels, gray, 77, 42);
        ink(67, 42, 3, 93);
        ink(67, 100, 17, 3);
        ink(81, 101, 3, 29);
        if (closed) ink(67, 130, 17, 3);
        var proof = OmrScoreInterpreter.class.getDeclaredMethod("stemEndsInClosedLetter",
                byte[].class, int.class, int.class, component, float.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, gray, W, H, head(77, 42), G);
    }

    @Test public void closedCounterBeyondThePrintedShaftIsAnnotationInk() throws Exception {
        assertTrue(letterCounter(true));
    }

    @Test public void anOpenCurveDoesNotProveAnAttachedLetter() throws Exception {
        assertFalse(letterCounter(false));
    }

    private Object staff(float top, int index) throws Exception {
        var s = construct(staff, new Class<?>[]{float.class, float.class, float.class},
                top, top + G * 4, G);
        for (var name : List.of("index", "count")) {
            var f = staff.getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(s, name.equals("count") ? 2 : index);
        }
        return s;
    }

    private boolean touchesForeignRail(int y) throws Exception {
        oval(labels, gray, 78, 82);
        oval(labels, gray, 168, 87);
        oval(labels, gray, 117, y + 40);
        ink(68, 82, 2, y - 82 + 5);
        ink(158, 87, 2, y - 87 + 5);
        ink(126, y, 2, 41);
        ink(68, y, 92, 8);
        var upper = staff(64, 0);
        var lower = staff(226, 1);
        var bass = head(117, y + 40);
        var proof = OmrScoreInterpreter.class.getDeclaredMethod("highQuarterTouchesUpperStaffBeam",
                byte[].class, int.class, int.class, component, staff, List.class, List.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, gray, W, H, bass, lower,
                List.of(head(78, 82), bass, head(168, 87)), List.of(upper, lower));
    }

    @Test public void aRailAtTheUpperStaffRuleHasItsOwnBracketingOwners() throws Exception {
        assertTrue(touchesForeignRail(128));
    }

    @Test public void anInterstaffRailKeepsItsCrossStaffEvidence() throws Exception {
        assertFalse(touchesForeignRail(144));
    }
}
