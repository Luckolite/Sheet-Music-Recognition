// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class StemlessWholeFourthTest {
    private static final int WIDTH = 320, HEIGHT = 250, GAP = 20, X = 125, BOTTOM = 125;
    private static final int Y = BOTTOM + 30;

    @Test
    public void wholeStoppedNoteAndPrintedDiamondProduceOneHarmonic() {
        var notes = apply(true, true, -1, -1);
        assertEquals(1, notes.size());
        assertEquals(-3, notes.get(0).staffStep());
        assertEquals(24, notes.get(0).harmonicSemitones());
        assertEquals(4f, notes.get(0).unbeamedDurationBeats(), .001f);
    }

    @Test
    public void ordinaryChordsAndAnUnprovedIntervalArePreserved() {
        assertEquals(2, apply(false, false, -1, -1).size());
        assertEquals(2, apply(true, false, -1, -1).size());
        assertEquals(2, apply(true, true, 0, -1).size());
    }

    @Test
    public void standaloneExportsTheProvedTouchAccidentalAndSoundingPitch() throws Exception {
        var stopped = apply(true, true, -1, -1).get(0);
        var identity = Main.class.getDeclaredMethod("noteIdentity", ScoreNoteEvent.class, int.class);
        identity.setAccessible(true);
        var event = (java.util.Map<?, ?>) identity.invoke(null, stopped, -1);
        assertEquals(58, event.get("writtenMidi"));
        assertEquals(63, event.get("harmonicTouchMidi"));
        assertEquals(82, event.get("midi"));
    }

    private static List<ScoreNoteEvent> apply(
            boolean decodedDiamond, boolean printedDiamond, int key, int touchAccidental) {
        var notes = List.of(note(-3, Y, ScoreNoteEvent.ACCIDENTAL_FROM_KEY),
                note(0, BOTTOM, touchAccidental));
        var page = new ScorePageInterpretation(
                List.of(new MeasureRegion(0, 1, .1f, .8f)), notes, 1,
                List.of(new ScoreKeyChange(0, key)), List.of());
        return ArtificialHarmonics.applyStemlessWholeFourths(
                page, image(decodedDiamond), image(printedDiamond), WIDTH, HEIGHT).notes();
    }

    private static ScoreNoteEvent note(int staffStep, int y, int accidental) {
        return new ScoreNoteEvent(0, X / (float) WIDTH, staffStep, 0, 1, y / (float) HEIGHT,
                false, 0, 0, accidental, 4f).withClef(ScoreNoteEvent.CLEF_TREBLE);
    }

    private static byte[] image(boolean diamond) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int line = 0; line < 5; line++)
            Arrays.fill(gray, (BOTTOM - line * GAP) * WIDTH + 20,
                    (BOTTOM - line * GAP) * WIDTH + WIDTH - 20, (byte) 0);
        for (int yy = Y - 10; yy <= Y + 10; yy++)
            for (int xx = X - 16; xx <= X + 16; xx++) {
                double ellipse = Math.hypot((xx - X) / 15., (yy - Y) / 8.);
                if (ellipse >= .60 && ellipse <= 1) gray[yy * WIDTH + xx] = 0;
            }
        for (int yy = BOTTOM - 13; yy <= BOTTOM + 13; yy++)
            for (int xx = X - 14; xx <= X + 14; xx++) {
                double distance = diamond ? (Math.abs(xx - X) + Math.abs(yy - BOTTOM)) / 12.
                        : Math.hypot((xx - X) / 14., (yy - BOTTOM) / 8.);
                if (distance >= .70 && distance <= 1.08) gray[yy * WIDTH + xx] = 0;
            }
        return gray;
    }
}
