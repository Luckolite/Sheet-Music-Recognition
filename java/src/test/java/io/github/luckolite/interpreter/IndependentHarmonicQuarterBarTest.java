// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class IndependentHarmonicQuarterBarTest {
    private static final int W = 520, H = 275;
    private static final List<MeasureRegion> BARS =
            List.of(bar(31, 143), bar(161, 282), bar(300, 447), bar(465, 509));
    private static final float[] BEATS = {2, 2, 2, 1};

    @Test
    public void twoClosedHarmonicQuartersOwnTheBar() {
        var score = score(2);
        var gray = paper();
        var corrected = ClosedDoubleHarmonicQuarters.apply(score, gray, W, H);
        assertEquals(List.of(0, 0, 0, 0),
                corrected.notes().stream().map(ScoreNoteEvent::beamCount).toList());
        assertEquals(List.of(1f, 1f, 1f, 1f),
                corrected.notes().stream().map(ScoreNoteEvent::unbeamedDurationBeats).toList());
        var onset = Map.of(0, 0, 1, 0, 2, 1, 3, 1);
        assertEquals(onset, ClosedDoubleHarmonicQuarters.writtenOnsets(score, gray, W, H, BEATS));
        assertEquals(onset, ClosedDoubleHarmonicQuarters.writtenOnsets(corrected, gray, W, H, BEATS));
    }

    @Test
    public void trueDividerAndBothDiamondsAreRequired() {
        var score = score(2);
        var noDivider = paper();
        fill(noDivider, 289, 112, 294, 184, 255);
        var noDiamond = paper();
        fill(noDiamond, 244, 72, 251, 84, 255);
        for (var variant : List.of(noDivider, noDiamond)) {
            assertSame(score, ClosedDoubleHarmonicQuarters.apply(score, variant, W, H));
            assertEquals(Map.of(), ClosedDoubleHarmonicQuarters.writtenOnsets(score, variant, W, H, BEATS));
        }

        var tripleMeter = score(3);
        assertSame(tripleMeter, ClosedDoubleHarmonicQuarters.apply(tripleMeter, paper(), W, H));
    }

    private static ScorePageInterpretation score(int beats) {
        return new ScorePageInterpretation(
                BARS, List.of(note(187, 116, 8, false), note(188, 156, 3, false),
                              note(244, 100, 10, false), note(245, 132, 6, true)),
                0, List.of(), List.of(), List.of(new ScoreMeterChange(0, beats, 4)));
    }

    private static byte[] paper() {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y : new int[] {116, 132, 148, 164, 180}) fill(gray, 30, y, 508, y, 0);
        fill(gray, 151, 116, 153, 180, 0);
        fill(gray, 290, 116, 292, 180, 0);
        fill(gray, 455, 116, 457, 180, 0);
        fill(gray, 171, 92, 204, 92, 0);
        fill(gray, 228, 76, 261, 76, 0);
        fill(gray, 228, 100, 261, 100, 0);
        fill(gray, 228, 108, 261, 108, 0);
        draw(gray, 187, 116, 188, 156, 196);
        draw(gray, 244, 100, 245, 132, 173);
        return gray;
    }

    private static void draw(byte[] gray, int x0, int y0, int x1, int y1, int end) {
        fill(gray, x0 - 11, y0 - 24, x0 - 9, end, 0);
        diamond(gray, x0, y0 - 24);
        diamond(gray, x1, y1 - 24);
        fill(gray, x0 - 10, y0 - 4, x0 + 8, y0 + 4, 0);
        fill(gray, x1 - 10, y1 - 4, x1 + 8, y1 + 4, 0);
    }

    private static void diamond(byte[] gray, int x, int y) {
        for (int dy = -11; dy <= 11; dy++)
            for (int dx = -11; dx <= 11; dx++) {
                int radius = Math.abs(dx) + Math.abs(dy);
                if (radius >= 7 && radius <= 12) gray[(y + dy) * W + x + dx] = 0;
            }
    }

    private static ScoreNoteEvent note(int x, int y, int step, boolean beamAlias) {
        return new ScoreNoteEvent(1, (x - 161f) / 121, step, 0, 1, y / (float) H, false,
                0, beamAlias ? 1 : 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, beamAlias ? 0 : 1, 1,
                0, 0, 30, false, 0, false, 2, 0, 1, -1, ScoreNoteEvent.Kind.PITCHED,
                ScoreNoteEvent.VisualSize.FULL);
    }

    private static MeasureRegion bar(int left, int right) {
        return new MeasureRegion(left / (float) W, right / (float) W, 84f / H, 216f / H);
    }

    private static void fill(byte[] image, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(image, y * W + left, y * W + right + 1, (byte) value);
    }
}
