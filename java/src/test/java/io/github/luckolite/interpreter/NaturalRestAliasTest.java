// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class NaturalRestAliasTest {
    private static final int W = 540, H = 230, G = 16, EDGE = 40, END = 449;
    private static final int TOP = 90, FIRST_X = 66, SECOND_X = 115, NOTE_Y = 106;
    private final byte[] gray = new byte[W * H];
    private final List<MeasureRegion> regions =
            List.of(new MeasureRegion(EDGE / (float) W, END / (float) W, 57f / H, 188f / H));
    private final ScoreRestEvent alias =
            new ScoreRestEvent(0, place(92), NOTE_Y / (float) H, 42f / H, 0, 1, 1);
    private final List<ScoreNoteEvent> notes = new ArrayList<>();

    public NaturalRestAliasTest() {
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= TOP + 4 * G; y += G) stroke(gray, EDGE, y, END, y, 1, 0);
        for (int i = 0; i < 12; i++) {
            int x = i == 0 ? FIRST_X : SECOND_X + (i - 1) * 31;
            int y = i == 0 ? 122 : NOTE_Y;
            stroke(gray, x - 6, y - 4, x + 5, y + 1, 4, 0);
            stroke(gray, x - 7, y, x - 7, 157, 3, 0);
            if (i % 3 == 2) stroke(gray, (i == 2 ? FIRST_X : x - 62) - 7, 153, x - 7, 153, 5, 0);
            notes.add(
                    eighth(
                            x,
                            y,
                            i == 1
                                    ? ScoreNoteEvent.ACCIDENTAL_NATURAL
                                    : ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        }
        stroke(gray, 83, 81, 83, 113, 4, 0);
        stroke(gray, 95, 98, 95, 130, 4, 0);
        stroke(gray, 83, 102, 95, 98, 4, 0);
        stroke(gray, 83, 113, 95, 109, 4, 0);
    }

    @Test
    public void removesAliasOnlyWhenBothRastersProveNaturalAndSharedBeam() {
        assertEquals(List.of(), filter(gray));
        byte[] noNatural = gray.clone();
        stroke(noNatural, 81, 79, 81, 134, 20, 255);
        assertEquals(List.of(alias), filter(noNatural));
        byte[] noBeam = gray.clone();
        stroke(noBeam, 75, 152, 98, 152, 10, 255);
        assertEquals(List.of(alias), filter(noBeam));
    }

    @Test
    public void printedNaturalRequiresAccidentalOwnershipOnFollowingHead() {
        notes.set(1, eighth(SECOND_X, NOTE_Y, ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        assertEquals(List.of(alias), filter(gray));
    }

    private static ScoreNoteEvent eighth(int x, int y, int accidental) {
        return new ScoreNoteEvent(
                0,
                place(x),
                (TOP + 4 * G - y) / (G / 2),
                0,
                1,
                y / (float) H,
                false,
                0,
                1,
                accidental,
                0,
                1,
                0,
                0,
                30,
                false,
                0,
                false,
                0,
                0,
                1,
                -1,
                ScoreNoteEvent.Kind.PITCHED);
    }

    private List<ScoreRestEvent> filter(byte[] source) {
        return RestCompetingGlyphs.remove(
                List.of(alias), notes, regions, new byte[gray.length], gray, source, W, H);
    }

    private static float place(int x) {
        return (x - EDGE) / (float) (END - EDGE);
    }

    private static void stroke(byte[] pixels, int x0, int y0, int x1, int y1, int size, int ink) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int step = 0; step <= steps; step++) {
            int x = x0 + Math.round((x1 - x0) * step / (float) steps);
            int y = y0 + Math.round((y1 - y0) * step / (float) steps);
            for (int dy = 0; dy < size; dy++)
                Arrays.fill(pixels, (y + dy) * W + x, (y + dy) * W + x + size, (byte) ink);
        }
    }
}
