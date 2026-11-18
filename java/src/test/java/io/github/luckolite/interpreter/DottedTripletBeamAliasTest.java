// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class DottedTripletBeamAliasTest {
    private static final int W = 280, H = 240;
    private static final float GAP = 16;

    @Test
    public void slopedRailExplainsTheFalseHeadOnlyWhileItsContourIsOwned() throws Exception {
        byte[] ink = plate();
        assertTrue(owns(ink));
        byte[] missingBridge = ink.clone();
        rectangle(missingBridge, 70, 143, 82, 161, 255);
        assertFalse(owns(missingBridge));
        byte[] addedHead = ink.clone();
        for (int dy = -6; dy <= 6; dy++)
            for (int dx = -11; dx <= 11; dx++)
                if (dx * dx / 121f + dy * dy / 36f <= 1f) addedHead[(150 + dy) * W + 94 + dx] = 0;
        assertFalse(owns(addedHead));
    }

    private static boolean owns(byte[] ink) throws Exception {
        Class<?> owner = PrintedDottedTripletBar.class;
        String prefix = owner.getName() + "$";
        var raster = Class.forName(prefix + "Raster");
        var head = Class.forName(prefix + "Head");
        var frame = raster.getDeclaredConstructor(byte[].class, int.class, int.class);
        var note =
                head.getDeclaredConstructor(
                        int.class, ScoreNoteEvent.class, float.class, float.class);
        frame.setAccessible(true);
        note.setAccessible(true);
        var test =
                owner.getDeclaredMethod(
                        "ownsTripletBeamGuest",
                        raster,
                        StaffPitchTrack.class,
                        head,
                        head,
                        head,
                        head,
                        float.class);
        test.setAccessible(true);
        var track =
                StaffPitchTrack.fromVerifiedSamples(
                        List.of(
                                new float[] {35, 174, GAP},
                                new float[] {95, 174, GAP},
                                new float[] {155, 174, GAP}));
        return (boolean)
                test.invoke(
                        null,
                        frame.newInstance(ink, W, H),
                        track,
                        note.newInstance(0, typed(57, 86, -1, 1), 57f, 86f),
                        note.newInstance(1, typed(107, 94, -1, 2), 107f, 94f),
                        note.newInstance(2, typed(142, 127, -1, 1), 142f, 127f),
                        note.newInstance(3, typed(94, 150, 1, 0), 94f, 150f),
                        GAP);
    }

    private static ScoreNoteEvent typed(int x, int y, int stem, int beams) {
        return new ScoreNoteEvent(
                        0,
                        x / (float) W,
                        Math.round((174 - y) * 2 / GAP),
                        0,
                        1,
                        y / (float) H,
                        false,
                        0,
                        beams,
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                        beams == 0 ? 1 : 0)
                .withClef(ScoreNoteEvent.CLEF_TREBLE)
                .withStemDirection(stem);
    }

    private static byte[] plate() {
        byte[] ink = new byte[W * H];
        Arrays.fill(ink, (byte) 255);
        for (int line = 110; line <= 174; line += (int) GAP) rectangle(ink, 17, line, 223, line, 0);
        for (int i = 0; i < 3; i++) {
            int x = new int[] {57, 107, 142}[i], y = new int[] {86, 94, 127}[i];
            rectangle(ink, x - 9, y - 5, x + 8, y + 5, 0);
            int stem = x - 9;
            int bottom = 150 + Math.round((stem - 48) * .235f);
            rectangle(ink, stem - 1, y, stem + 1, bottom, 0);
        }
        for (int x = 47; x <= 134; x++) {
            int bottom = 150 + Math.round((x - 48) * .235f);
            rectangle(ink, x, bottom - 8, x, bottom, 0);
            if (x >= 87 && x <= 98) rectangle(ink, x, bottom - 18, x, bottom - 13, 0);
        }
        return ink;
    }

    private static void rectangle(byte[] gray, int l, int t, int r, int b, int value) {
        for (int y = t; y <= b; y++) Arrays.fill(gray, y * W + l, y * W + r + 1, (byte) value);
    }
}
