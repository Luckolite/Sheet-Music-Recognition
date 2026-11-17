// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertArrayEquals;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class OuterLedgerStaccatoOwnershipTest {
    private static final int WIDTH = 480, HEIGHT = 400;
    private static final int NOTE_X = 240, NOTE_Y = 195, GAP = 16;
    private static final int STAFF_TOP = 274;

    private static byte[] paper(int dotY, int thickness, int reach, boolean broken) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = dotY - 2; y <= dotY + 2; y++)
            for (int x = NOTE_X - 2; x <= NOTE_X + 2; x++) gray[y * WIDTH + x] = 30;
        for (int x = NOTE_X - reach; x <= NOTE_X + reach; x++) {
            if (broken && x >= NOTE_X + 21 && x <= NOTE_X + 25) continue;
            int y = 170 + Math.floorDiv(x - NOTE_X, 60);
            for (int t = 0; t < thickness; t++) gray[(y + t) * WIDTH + x] = 40;
        }
        return gray;
    }

    private static int[] marks(byte[] gray, byte[] original) {
        var notes = List.of(
                new NoteArticulationDetector.Anchor(
                        NOTE_X, NOTE_Y, GAP, 0, null, STAFF_TOP, STAFF_TOP + 4 * GAP, 0, 1),
                new NoteArticulationDetector.Anchor(
                        NOTE_X + 2, NOTE_Y + 40, GAP, 0, null,
                        STAFF_TOP, STAFF_TOP + 4 * GAP, 0, 1));
        int[] marks = NoteArticulationDetector.detect(
                new byte[gray.length], gray, WIDTH, HEIGHT, notes, original);
        return new int[] {marks[0] & NoteArticulation.STACCATO,
                marks[1] & NoteArticulation.STACCATO};
    }

    @Test
    public void thinContinuousRuleInBothPlanesSeparatesOuterLedgerSpeck() {
        var gray = paper(143, 3, 86, false);
        assertArrayEquals(new int[] {0, 0}, marks(gray, gray.clone()));
    }

    @Test
    public void unseparatedAndShortLedgerDotsRetainTheChord() {
        int mark = NoteArticulation.STACCATO;
        for (byte[] gray : List.of(paper(143, 0, 0, false), paper(143, 3, 18, false)))
            assertArrayEquals(new int[] {mark, mark}, marks(gray, gray.clone()));
    }

    @Test
    public void longRuleMustBeThinAndContinuousOnBothPlanes() {
        int mark = NoteArticulation.STACCATO;
        byte[] complete = paper(143, 3, 86, false);
        for (byte[] gray : List.of(paper(143, 3, 86, true), paper(143, 7, 86, false))) {
            assertArrayEquals(new int[] {mark, mark}, marks(gray, gray.clone()));
            assertArrayEquals(new int[] {mark, mark}, marks(complete, gray));
        }
    }

    @Test
    public void dotBetweenGuideRuleAndHighChordStillBelongsToChord() {
        int mark = NoteArticulation.STACCATO;
        byte[] gray = paper(182, 3, 86, false);
        assertArrayEquals(new int[] {mark, mark}, marks(gray, gray.clone()));
    }
}
