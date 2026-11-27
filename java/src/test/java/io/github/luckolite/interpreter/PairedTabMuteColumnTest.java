// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PairedTabMuteColumnTest {
    private static final int WIDTH = 360, HEIGHT = 260, X = 180, TOP = 100, GAP = 24;

    @Test public void mergedCrossTokenRequiresIndependentCrossInkOnThreeStrings() throws Exception {
        var recovered = supplement(raster(6), List.of());
        assertEquals(6, recovered.frets().size());
        for (var fret : recovered.frets()) assertEquals(-1, fret.fret());
        assertTrue(supplement(raster(2), List.of()).frets().isEmpty());
        assertTrue(supplement(raster(0), List.of()).frets().isEmpty());
    }

    @Test public void preservesExistingNumericFretAgainstConflictingToken() throws Exception {
        var frets = List.of(new TablatureDecoder.Fret(X, TOP, 0, 4));
        assertEquals(frets, supplement(raster(6), frets).frets());
    }

    @Test public void provesOneOwnedRhythmicRailWithoutErasingAnExtraBeam() throws Exception {
        byte[] gray = raster(6);
        for (int y = 2; y <= 39; y++) gray[y * WIDTH + X + 9] = 25;
        beam(gray, 2);
        var eighths = supplement(gray, List.of()).frets();
        assertEquals(6, eighths.size());
        assertTrue(eighths.stream().allMatch(f -> f.beams() == 1));
        beam(gray, 12);
        var ambiguous = supplement(gray, List.of()).frets();
        assertEquals(6, ambiguous.size());
        assertTrue(ambiguous.stream().allMatch(f -> f.beams() == 0));
    }

    private static void beam(byte[] gray, int firstRow) {
        for (int y = firstRow; y < firstRow + 5; y++)
            Arrays.fill(gray, y * WIDTH + X - 32, y * WIDTH + X + 45, (byte) 25);
    }

    @Test public void recoversPairedMutedStrokeWithoutPitchAuthority() throws Exception {
        var measures = List.of(new MeasureRegion(.1f, .9f, .06f, .29f));
        var ordinary = new ScoreNoteEvent(0, .7f, 2, 0, 1, .15f, false);
        var notes = List.of(new ScoreNoteEvent(0, .5f, -2, 0, 1, .18f, false).withOctaveShift(1),
                new ScoreNoteEvent(0, .5f, 1, 0, 1, .16f, false), ordinary);
        var score = new ScorePageInterpretation(
                measures, notes, 1, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var result = TablatureDecoder.apply(score, List.of(supplement(raster(6), List.of())),
                WIDTH, HEIGHT);
        assertEquals(3, result.notes().size());
        assertEquals(ordinary, result.notes().get(2));
        for (var note : result.notes().subList(0, 2)) {
            assertEquals(ScoreNoteEvent.Kind.UNPITCHED, note.kind());
            assertEquals(0, note.octaveShift());
            assertEquals(TabEffect.encode(TabEffect.DEAD, 0), note.articulations() & TabEffect.ALL);
        }
    }

    private static TablatureDecoder.Staff supplement(
            byte[] gray, List<TablatureDecoder.Fret> frets) throws Exception {
        var staff = new TablatureDecoder.Staff(TOP, GAP, 30, frets, List.of(40f, 320f));
        var word = new TablatureDecoder.Word("**X***", (X - 8f) / WIDTH, .34f,
                (X + 8f) / WIDTH, .89f);
        return TabFretRaster.supplement(gray, WIDTH, HEIGHT, List.of(staff), List.of(word), null).get(0);
    }

    private static byte[] raster(int crosses) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int string = 0; string < 6; string++) {
            int y = TOP + string * GAP;
            Arrays.fill(gray, y * WIDTH + 40, y * WIDTH + 321, (byte) 100);
            if (string >= crosses) continue;
            for (int dy = -10; dy <= 10; dy++) {
                int offset = Math.round(Math.abs(dy) * .55f);
                for (int dx = -2; dx <= 2; dx++) {
                    gray[(y + dy) * WIDTH + X - offset + dx] = 25;
                    gray[(y + dy) * WIDTH + X + offset + dx] = 25;
                }
            }
        }
        return gray;
    }
}
