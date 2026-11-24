// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class SingleBeamedLedgerFourthTest {
    private static final int WIDTH = 210;
    private static final int HEIGHT = 221;
    private static final int GAP = 18;
    private static final int STOP_X = 96;
    private static final int STOP_Y = 91;
    private static final int DIAMOND_X = 92;
    private static final int DIAMOND_Y = 64;
    private static final int SHAFT_X = 85;
    private static final List<MeasureRegion> MEASURES = List.of(new MeasureRegion(0, 1, 0, 1));
    private static final List<PlayingTechniqueDetector.Staff> STAFFS =
            List.of(new PlayingTechniqueDetector.Staff(100, 172, GAP, 0, 1));
    private final byte[] labels = new byte[WIDTH * HEIGHT];
    private final byte[] gray = new byte[labels.length];
    private final ScoreNoteEvent stop = note(STOP_X, STOP_Y, 9);
    private final ScoreNoteEvent touch = note(94, 67, 12);
    private final List<ScoreNoteEvent> pair = List.of(touch, stop);

    @Test
    public void onePrintedBeamWithLedgerThroughDiamondSoundsOnlyStoppedFourth() {
        draw(true);
        assertEquals(List.of(stop.withOctaveShift(2)), decode(gray, gray));
    }

    @Test
    public void bothPlanesMustCarryTheSameShaft() {
        draw(true);
        byte[] cut = gray.clone();
        for (int y = DIAMOND_Y + 8; y <= DIAMOND_Y + 15; y++)
            Arrays.fill(cut, y * WIDTH + SHAFT_X - 1, y * WIDTH + SHAFT_X + 4, (byte) 255);
        assertEquals(pair, decode(gray, cut));
        assertEquals(pair, decode(cut, gray));
        assertEquals(pair, decode(gray, null));
    }

    @Test
    public void roundFilledFourthWithSameLedgerAndShaftRemainsTwoAttacks() {
        draw(false);
        assertEquals(pair, decode(gray, gray));
    }

    private List<ScoreNoteEvent> decode(byte[] normalized, byte[] original) {
        return ArtificialHarmonics.apply(labels, normalized, original, WIDTH, HEIGHT, MEASURES, pair, STAFFS);
    }

    private void draw(boolean diamond) {
        Arrays.fill(gray, (byte) 255);
        for (int dy = -10; dy <= 10; dy++) {
            for (int dx = -10; dx <= 10; dx++) {
                boolean ink = diamond
                        ? Math.abs(dx) + Math.abs(dy) <= 10 && !(dy >= -6 && dy <= -4 && Math.abs(dx) <= 2)
                        : dx * dx / 100f + dy * dy / 64f <= 1;
                if (ink) paint(DIAMOND_X + dx, DIAMOND_Y + dy, diamond
                        ? OmrMeasurePostProcessor.SYMBOL : OmrMeasurePostProcessor.NOTEHEAD);
            }
        }
        for (int dy = -8; dy <= 8; dy++)
            for (int dx = -11; dx <= 11; dx++)
                if (dx * dx / 121f + dy * dy / 64f <= 1)
                    paint(STOP_X + dx, STOP_Y + dy, OmrMeasurePostProcessor.NOTEHEAD);
        for (int rule : new int[] {63, 82, 100, 118})
            for (int x = STOP_X - 18; x <= STOP_X + 15; x++)
                for (int y = rule - 1; y <= rule + 1; y++) paint(x, y, OmrMeasurePostProcessor.SYMBOL);
        for (int x = SHAFT_X; x <= SHAFT_X + 2; x++)
            for (int y = DIAMOND_Y; y <= STOP_Y + 48; y++) paint(x, y, (byte) 1);
    }

    private void paint(int x, int y, byte label) {
        gray[y * WIDTH + x] = 0;
        labels[y * WIDTH + x] = label;
    }

    private static ScoreNoteEvent note(int x, int y, int step) {
        return new ScoreNoteEvent(0, x / (float) WIDTH, step, 0, 1, y / (float) HEIGHT, false, 0, 1,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE).withStemDirection(-1);
    }
}
