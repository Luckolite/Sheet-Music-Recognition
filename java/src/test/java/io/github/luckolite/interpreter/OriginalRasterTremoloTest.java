// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original hollow stem with three short strokes removed by raster preparation. */
public class OriginalRasterTremoloTest {
    private static final int WIDTH = 320, HEIGHT = 255, GAP = 16;
    private static final int HEAD_X = 166, HEAD_Y = 170, STEM_X = HEAD_X + 9;

    private record Page(byte[] labels, byte[] prepared, byte[] original) {
        List<ScoreNoteEvent> notes(byte[] source) {
            return OmrScoreInterpreter.analyze(labels, prepared, WIDTH, HEIGHT,
                    List.of(new MeasureRegion(.10f, .94f, .25f, .83f)), source).notes();
        }
    }

    private static Page page(boolean hollow, int strokes) {
        byte[] labels = new byte[WIDTH * HEIGHT], prepared = new byte[WIDTH * HEIGHT];
        Arrays.fill(prepared, (byte) 255);
        for (int y = 96; y <= 160; y += GAP)
            for (int x = 28; x <= 300; x++) {
                labels[y * WIDTH + x] = 4;
                prepared[y * WIDTH + x] = 0;
            }
        for (int dy = -7; dy <= 7; dy++)
            for (int dx = -11; dx <= 11; dx++) {
                double y = dy + .26 * dx;
                if (dx * dx / 121.0 + y * y / 39.0 > 1) continue;
                int pixel = (HEAD_Y + dy) * WIDTH + HEAD_X + dx;
                labels[pixel] = 2;
                if (!hollow || dx * dx / 45.0 + y * y / 13.0 >= 1) prepared[pixel] = 0;
            }
        for (int y = 104; y <= HEAD_Y; y++) prepared[y * WIDTH + STEM_X] = 0;
        byte[] original = prepared.clone();
        for (int stroke = 0; stroke < strokes; stroke++)
            for (int dx = -10; dx <= 10; dx++)
                for (int dy = -2; dy <= 2; dy++) {
                    int y = 113 + 12 * stroke + Math.round(-.30f * dx) + dy;
                    original[y * WIDTH + STEM_X + dx] = 0;
                }
        return new Page(labels, prepared, original);
    }

    @Test
    public void hollowHeadRetainsThreeSourceStrokesThroughPreparation() {
        var page = page(true, 3);
        assertNotNull(AttachedTremoloInk.find(page.original, WIDTH, HEIGHT, STEM_X, 163, 104, -1, GAP));
        var notes = page.notes(page.original);
        assertEquals(1, notes.size());
        var note = notes.get(0);
        assertEquals(note.toString(), 3, NoteOrnament.tremoloBeams(note.articulations()));
        assertEquals(2, note.unbeamedDurationBeats(), 0);
        assertEquals(0, note.beamCount());
        assertEquals(note.staffStep(), page.notes(page.prepared).get(0).staffStep());
        assertEquals(0, NoteOrnament.tremoloBeams(page.notes(page.prepared).get(0).articulations()));
    }

    @Test
    public void fewerSourceStrokesCannotSupplyAThreeStrokeMark() {
        var page = page(true, 2);
        var notes = page.notes(page.original);
        assertEquals(1, notes.size());
        assertEquals(0, NoteOrnament.tremoloBeams(notes.get(0).articulations()));
    }

    @Test
    public void aFilledHeadCannotUseTheHollowHeadFallback() {
        var page = page(false, 3);
        var notes = page.notes(page.original);
        assertEquals(1, notes.size());
        assertEquals(1, notes.get(0).unbeamedDurationBeats(), 0);
        assertEquals(0, NoteOrnament.tremoloBeams(notes.get(0).articulations()));
    }
}
