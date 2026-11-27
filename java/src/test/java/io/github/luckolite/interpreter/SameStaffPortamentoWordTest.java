// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public final class SameStaffPortamentoWordTest {
    private static final int W = 640, H = 320;
    private static final PlayingTechniqueDetector.Word CAPTION =
            new PlayingTechniqueDetector.Word("port", 192f / W, 131f / H, 277f / W, 164f / H);
    private static final int CONNECTED_FROM_ABOVE =
            NoteOrnament.SLIDE | NoteOrnament.FROM_PREVIOUS | NoteOrnament.FROM_ABOVE;

    private static ScoreNoteEvent note(int x, int y, int step, int duration, int stem) {
        return new ScoreNoteEvent(0, x / (float) W, step, 0, 1, y / (float) H,
                false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, duration, 1, 0)
                .withStemDirection(stem);
    }

    private static byte[] raster(boolean connection, boolean stemmedTextHead) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y = 120; y <= 184; y += 16)
            Arrays.fill(gray, y * W + 20, y * W + W - 20, (byte) 0);
        oval(gray, 155, 152, 10, 6, false);
        oval(gray, 304, 168, 10, 6, false);
        oval(gray, 229, 146, 9, 7, !stemmedTextHead);
        if (stemmedTextHead)
            for (int y = 109; y <= 146; y++) gray[y * W + 237] = 0;
        for (int y = 116; y <= 151; y++) gray[y * W + 164] = 0;
        for (int y = 128; y <= 167; y++) gray[y * W + 313] = 0;
        if (connection)
            for (int x = 172; x <= 285; x++) {
                int y = (int) Math.round(153 + (x - 172) * 14. / 113);
                gray[y * W + x] = 0;
                gray[(y + 1) * W + x] = 0;
            }
        return gray;
    }

    private static void oval(byte[] gray, int cx, int cy, int rx, int ry, boolean hollow) {
        for (int y = cy - ry; y <= cy + ry; y++)
            for (int x = cx - rx; x <= cx + rx; x++) {
                double d = Math.pow((x - cx) / (double) rx, 2) + Math.pow((y - cy) / (double) ry, 2);
                if (d <= 1 && (!hollow || d >= .44)) gray[y * W + x] = 0;
            }
    }

    private static final List<PortableNoteOrnaments.Anchor> ANCHORS = List.of(
            new PortableNoteOrnaments.Anchor(155, 152, 16, 0, 0),
            new PortableNoteOrnaments.Anchor(229, 146, 16, 0, 0),
            new PortableNoteOrnaments.Anchor(304, 168, 16, 0, 0));

    private static List<ScoreNoteEvent> notes(boolean realHead) {
        return List.of(note(155, 152, 4, 1, -1),
                note(229, 146, 5, realHead ? 1 : 4, realHead ? -1 : 0),
                note(304, 168, 2, 1, -1));
    }

    private static boolean[] classify(boolean line, boolean realHead, int[] marks) {
        boolean[] textHeads = new boolean[3];
        PortableNoteOrnaments.applySameStaffPortamentoWords(List.of(CAPTION), raster(line, realHead),
                W, H, ANCHORS, notes(realHead), marks, textHeads);
        return textHeads;
    }

    @Test
    public void independentlyDetectedConnectionStillDisownsItsCaptionLetter() {
        int[] marks = {0, 0, CONNECTED_FROM_ABOVE};
        assertArrayEquals(new boolean[] {false, true, false}, classify(true, false, marks));
        assertArrayEquals(new int[] {0, 0, CONNECTED_FROM_ABOVE}, marks);
    }

    @Test
    public void captionCannotOwnAStemmedAttackOrSupplyItsOwnConnection() {
        int[] marks = {0, 0, CONNECTED_FROM_ABOVE};
        assertArrayEquals(new boolean[3], classify(true, true, marks));
        assertArrayEquals(new boolean[3], classify(false, false, marks));
    }

    @Test
    public void unrelatedOrnamentCannotAuthorizeCaptionCleanup() {
        int[] marks = {0, 0, NoteOrnament.TRILL};
        assertArrayEquals(new boolean[3], classify(true, false, marks));
        assertEquals(NoteOrnament.TRILL, marks[2]);
    }
}
