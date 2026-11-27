// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class PrintedStaffTrackDisagreementTest {
    private static final int WIDTH = 460, HEIGHT = 210, GAP = 16, BOTTOM = 150, X = 230;

    @Test
    public void completeRulesKeepThePrintedPhaseDespiteTheShiftedTrack() throws Exception {
        assertEquals(BOTTOM + .5f, bottomAtHead(true), 1f);
    }

    @Test
    public void incompleteRulesDoNotOverrideTheTrack() throws Exception {
        assertEquals(BOTTOM - 2 * GAP, bottomAtHead(false), .01f);
    }

    private static float bottomAtHead(boolean complete) throws Exception {
        byte[] gray = new byte[WIDTH * HEIGHT], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int line = 0; line < (complete ? 5 : 3); line++) {
            int row = BOTTOM - line * GAP;
            Arrays.fill(gray, row * WIDTH + 25, (row + 1) * WIDTH - 25, (byte) 45);
        }
        var type = OmrScoreInterpreter.class;
        var staffType = Class.forName(type.getName() + "$Staff");
        var headType = Class.forName(type.getName() + "$Component");
        var staffConstructor = staffType.getDeclaredConstructor(float.class, float.class, float.class);
        staffConstructor.setAccessible(true);
        Object staff = staffConstructor.newInstance((float) BOTTOM - 4 * GAP, (float) BOTTOM, (float) GAP);
        var printedPhase = staffType.getDeclaredField("printedPhase");
        printedPhase.setAccessible(true);
        printedPhase.setBoolean(staff, true);
        var track = staffType.getDeclaredField("pitchTrack");
        track.setAccessible(true);
        track.set(staff, StaffPitchTrack.fromVerifiedSamples(List.of(
                new float[] {25, BOTTOM - 2 * GAP, GAP}, new float[] {WIDTH - 25, BOTTOM - 2 * GAP, GAP})));
        var headConstructor = headType.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        headConstructor.setAccessible(true);
        Object head = headConstructor.newInstance(70, X - 6, X + 6, BOTTOM - 27, BOTTOM - 21,
                (float) X, BOTTOM - 1.5f * GAP);
        var pitch = type.getDeclaredMethod("localStaffPitch",
                byte[].class, byte[].class, int.class, int.class, staffType, headType);
        pitch.setAccessible(true);
        return ((float[]) pitch.invoke(null, labels, gray, WIDTH, HEIGHT, staff, head))[0];
    }
}
