// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class ClosedArpeggioTripletsTest {
    private static final int WIDTH = 620, HEIGHT = 260, LEFT = 40, RIGHT = 582;
    private static final int FLOOR = 165;
    private static final int[] STEPS = {0, 3, 5, 7, 5, 3, 0, 1, 3, 5, 7, 5, 3, 1};

    @Test
    public void keepsTwoUnnumberedTripletsAndTwoStraightQuartets() {
        var score = score(4);
        var ink = raster();
        var fixed = ClosedArpeggioTriplets.restore(score, null, ink, ink.clone(), WIDTH, HEIGHT);
        assertEquals(List.of(3, 3, 3, 3, 3, 3, 1, 1, 1, 1, 1, 1, 1, 1),
                fixed.notes().stream().map(ScoreNoteEvent::tupletDivisor).toList());
        assertEquals(score.notes().subList(6, 14), fixed.notes().subList(6, 14));
        var groups = fixed.beamSource().groups();
        assertEquals(List.of(List.of(1, 1), List.of(1, 1), List.of(3, 3, 3), List.of(3, 3, 3)),
                groups.stream().map(ScoreBeamSource.Group::bridges).toList());
        assertEquals(ScoreBeamSource.TupletMark.UNNUMBERED, groups.get(0).tupletMark());
        assertEquals(ScoreBeamSource.TupletMark.UNNUMBERED, groups.get(1).tupletMark());
        assertEquals(score.beamSource().noteOrigins(), fixed.beamSource().noteOrigins());
    }

    @Test
    public void rejectsMismatchedInkOrMeter() {
        var score = score(4);
        var ink = raster();
        var erased = ink.clone();
        rectangle(erased, x(7) + 11, x(8) + 7, 67, 73, 255);
        assertSame(score, ClosedArpeggioTriplets.restore(score, null, ink, erased, WIDTH, HEIGHT));
        assertSame(score, ClosedArpeggioTriplets.restore(score, null, erased, ink, WIDTH, HEIGHT));
        var tripleMeter = score(3);
        assertSame(tripleMeter, ClosedArpeggioTriplets.restore(tripleMeter, null, ink, ink, WIDTH, HEIGHT));
    }

    private static ScorePageInterpretation score(int numerator) {
        var notes = new ArrayList<ScoreNoteEvent>();
        var origins = new ArrayList<Optional<ScoreBeamSource.Anchor>>();
        for (int i = 0; i < STEPS.length; i++) {
            int y = FLOOR - 8 * STEPS[i];
            notes.add(new ScoreNoteEvent(0, (x(i) - LEFT) / (float) (RIGHT - LEFT), STEPS[i],
                    0, 1, y / (float) HEIGHT, false, 0, i < 6 ? 1 : 2,
                    ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE,
                    false, 0, false, 0, 0, 1, 1, ScoreNoteEvent.Kind.PITCHED,
                    ScoreNoteEvent.VisualSize.FULL, 0, null));
            origins.add(Optional.of(new ScoreBeamSource.Anchor(0, i)));
        }
        var bar = new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH,
                67f / HEIGHT, 198f / HEIGHT);
        return new ScorePageInterpretation(List.of(bar), notes, 0, List.of(), List.of(),
                List.of(new ScoreMeterChange(0, numerator, 4)), List.of(), List.of(), List.of(), List.of())
                .withBeamSource(new ScoreBeamSource(List.of(), List.of(), origins));
    }

    private static byte[] raster() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = FLOOR - 64; y <= FLOOR; y += 16)
            rectangle(ink, LEFT, RIGHT + 20, y, y + 1, 0);
        rectangle(ink, RIGHT - 1, RIGHT + 1, FLOOR - 64, FLOOR, 0);
        for (int i = 0; i < STEPS.length; i++) {
            int y = FLOOR - 8 * STEPS[i];
            rectangle(ink, x(i) - 6, x(i) + 7, y - 4, y + 4, 0);
            rectangle(ink, x(i) + 8, x(i) + 10, 68, y, 0);
        }
        for (int[] group : new int[][]{{0, 2}, {3, 5}, {6, 9}, {10, 13}}) {
            rectangle(ink, x(group[0]) + 8, x(group[1]) + 10, 67, 73, 0);
            if (group[0] >= 6) rectangle(ink, x(group[0]) + 8, x(group[1]) + 10, 80, 86, 0);
        }
        return ink;
    }

    private static int x(int slot) { return 70 + 36 * slot; }

    private static void rectangle(byte[] ink, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
