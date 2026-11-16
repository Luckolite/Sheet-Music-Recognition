// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class HarmonicNaturalRestAliasTest {
    private static final int W = 400, H = 280;
    private static final int REST_X = 202, REST_Y = 116;
    private static final MeasureRegion BAR = new MeasureRegion(.1f, .9f, .235f, .7035714f);
    private static final List<ScoreRestEvent> RESTS = List.of(new ScoreRestEvent(
            0, position(REST_X), REST_Y / (float) H, 58f / H, 0, 1, .125, ScoreRestEvent.Kind.LITERAL));

    @Test
    public void connectedHarmonicsClaimTheJoinedAccidentalColumn() {
        var labels = new byte[W * H];
        var optical = image(labels);
        assertEquals(0, result(optical, optical, labels));
        assertEquals(0, result(optical, optical, labels, note(169, 104, 9)));
        assertEquals(1, result(optical, optical, labels, note(168, 98, 10)));
    }

    @Test
    public void glyphShaftAndBeamMustBePresentInBothPlanes() {
        var labels = new byte[W * H];
        var optical = image(labels);

        var erasedConnector = optical.clone();
        fill(erasedConnector, 194, 128, 200, 137, 255);
        assertEquals(1, result(optical, erasedConnector, labels));

        var erasedBeam = optical.clone();
        fill(erasedBeam, 188, 168, 195, 199, 255);
        assertEquals(1, result(optical, erasedBeam, labels));

        var erasedTouch = optical.clone();
        fill(erasedTouch, 224, 93, 235, 99, 255);
        assertEquals(1, result(optical, erasedTouch, labels));
    }

    private static int result(byte[] gray, byte[] source, byte[] labels) {
        return result(gray, source, labels, null);
    }

    private static int result(byte[] gray, byte[] source, byte[] labels, ScoreNoteEvent extra) {
        var left = note(174, 132, 6);
        var right = note(230, 124, 7);
        var heads = extra == null ? List.of(left, right) : List.of(extra, left, right);
        return RestCompetingGlyphs.remove(
                RESTS, heads,
                List.of(BAR), labels, gray, source, W, H).size();
    }

    private static byte[] image(byte[] labels) {
        var gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y : new int[] {104, 120, 136, 152, 168})
            fill(gray, 112, y, 295, y, 0);

        natural(labels, gray, 207, 100);
        natural(labels, gray, 198, 124);
        harmonic(gray, 174, 132, 190);
        harmonic(gray, 230, 124, 185);
        for (int x = 164; x <= 220; x++) {
            int end = Math.round(190 - (x - 164) * 5f / 56f);
            fill(gray, x, end - 4, x, end, 0);
            fill(gray, x, end - 14, x, end - 10, 0);
        }
        return gray;
    }

    private static float position(int x) {
        return (x / (float) W - BAR.left()) / (BAR.right() - BAR.left());
    }

    private static ScoreNoteEvent note(int x, int y, int step) {
        return new ScoreNoteEvent(0, position(x), step, 0, 1, y / (float) H, false, 0, 2,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0, 30, false, 0, false, 0, 0,
                1, -1, ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL);
    }

    private static void natural(byte[] labels, byte[] gray, int x, int y) {
        accidentalStroke(labels, gray, x - 5, y - 24, x - 3, y + 12);
        accidentalStroke(labels, gray, x + 4, y - 12, x + 6, y + 24);
        accidentalStroke(labels, gray, x - 5, y - 11, x + 6, y - 8);
        accidentalStroke(labels, gray, x - 5, y + 7, x + 6, y + 10);
    }

    private static void accidentalStroke(byte[] labels, byte[] gray, int x, int y, int r, int b) {
        fill(gray, x, y, r, b, 0);
        fill(labels, x, y, r, b, OmrMeasurePostProcessor.CLEF_OR_KEY);
    }

    private static void harmonic(byte[] gray, int x, int headY, int tailY) {
        int touchY = headY - 24;
        fill(gray, x - 11, touchY, x - 9, tailY, 0);
        for (int dy = -11; dy <= 11; dy++)
            for (int dx = -11; dx <= 11; dx++) {
                int radius = Math.abs(dx) + Math.abs(dy);
                if (radius >= 7 && radius <= 12) gray[(touchY + dy) * W + x + dx] = 0;
            }
        for (int dy = -5; dy <= 5; dy++)
            for (int dx = -10; dx <= 10; dx++)
                if (dx * dx / 100d + dy * dy / 25d <= 1)
                    gray[(headY + dy) * W + x + dx] = 0;
    }

    private static void fill(byte[] bytes, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(bytes, y * W + left, y * W + right + 1, (byte) value);
    }
}
