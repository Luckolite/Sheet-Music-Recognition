// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original drawings: a raster paper pocket inside a filled chord head. */
public class ArtificialHarmonicsInnerThirdTest {
    private static final int W = 240, H = 240, X = 100, Y = 160, GAP = 20;

    private byte[] filledThirdWithPaperPocket() {
        byte[] image = new byte[W * H];
        Arrays.fill(image, (byte) 255);
        for (int row = 120; row <= 170; row++)
            for (int col = 80; col <= 120; col++) {
                double dx = (col - X) / 10d;
                double dy = (row - (Y - GAP) - (col - X) * .4) / 11d;
                if (dx * dx + dy * dy <= 1) image[row * W + col] = 0;
                dx = (col - X) / 11d;
                dy = (row - Y) / 7d;
                if (dx * dx + dy * dy <= 1) image[row * W + col] = 0;
            }
        for (int row = 132; row <= 134; row++)
            for (int col = X - 1; col <= X + 1; col++) image[row * W + col] = (byte) 255;
        for (int row = 128; row <= 202; row++)
            for (int col = X - 11; col <= X - 9; col++) image[row * W + col] = 0;
        for (int row : new int[] {130, 150, 170, 190})
            for (int col = 30; col <= 210; col++) image[row * W + col] = 0;
        return image;
    }

    private ScoreNoteEvent note(int step, float x, float y) {
        return new ScoreNoteEvent(0, x / W, step, 0, 1, y / H, false, 0, 2,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 0, 0, 0, ScoreNoteEvent.CLEF_TREBLE)
                .withStemDirection(-1);
    }

    private List<ScoreNoteEvent> apply(byte[] image, List<ScoreNoteEvent> notes) {
        return ArtificialHarmonics.apply(image, W, H, List.of(new MeasureRegion(0, 1, 0, 1)), notes,
                List.of(new PlayingTechniqueDetector.Staff(130, 210, GAP, 0, 1)));
    }

    @Test
    public void filledInnerThirdDoesNotBecomeAnUnlabelledFourth() {
        byte[] image = filledThirdWithPaperPocket();
        var notes = List.of(note(0, X, Y), note(2, X, Y - GAP));
        assertEquals(notes, apply(image, notes));
    }

    @Test
    public void distantThirdCannotVetoAnOwnedTouch() {
        byte[] image = filledThirdWithPaperPocket();
        var notes = List.of(note(0, X, Y), note(2, X + GAP * 2, Y - GAP));
        assertEquals(2, apply(image, notes).get(0).octaveShift());
    }
}
