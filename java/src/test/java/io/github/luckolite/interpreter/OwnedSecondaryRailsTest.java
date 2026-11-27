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

public final class OwnedSecondaryRailsTest {
    private static final int WIDTH = 240;
    private static final int HEIGHT = 180;

    @Test
    public void restoresTheSecondaryRailForBothInferredEighths() {
        var score = quartet(List.of(3, 3, 3));
        var gray = raster(true);
        var result = ClosedMonophonicSixteenths.restoreOwnedSecondaryRails(
                score, gray, gray.clone(), WIDTH, HEIGHT);
        assertEquals(List.of(2, 2, 2, 2), result.notes().stream().map(ScoreNoteEvent::beamCount).toList());
        assertEquals(List.of(score.notes().get(0), score.notes().get(1).withBeamAndStem(2, 1),
                score.notes().get(2).withBeamAndStem(2, 1), score.notes().get(3)), result.notes());
        assertEquals(score.beamSource(), result.beamSource());
    }

    @Test
    public void refusesAnInterruptedRailInEitherRasterOrInTheOwnedTopology() {
        var score = quartet(List.of(3, 3, 3));
        var complete = raster(true);
        var interrupted = raster(false);
        assertSame(score, ClosedMonophonicSixteenths.restoreOwnedSecondaryRails(
                score, complete, interrupted, WIDTH, HEIGHT));
        assertSame(score, ClosedMonophonicSixteenths.restoreOwnedSecondaryRails(
                score, interrupted, complete, WIDTH, HEIGHT));
        var fewerBridges = quartet(List.of(3, 1, 3));
        assertSame(fewerBridges, ClosedMonophonicSixteenths.restoreOwnedSecondaryRails(
                fewerBridges, complete, complete, WIDTH, HEIGHT));
    }

    private static ScorePageInterpretation quartet(List<Integer> bridges) {
        var bar = new MeasureRegion(15f / WIDTH, 232f / WIDTH, 16f / HEIGHT, 147f / HEIGHT);
        var notes = new ArrayList<ScoreNoteEvent>();
        var origins = new ArrayList<Optional<ScoreBeamSource.Anchor>>();
        var columns = new ArrayList<ScoreBeamSource.Column>();
        for (int i = 0; i < 4; i++) {
            int x = headX(i), y = headY(i);
            notes.add(new ScoreNoteEvent(0, (x - 15f) / 217, (113 - y) / 8, 0, 1, y / (float) HEIGHT,
                    false, 0, i == 1 || i == 2 ? 1 : 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0)
                    .withStemDirection(1));
            var anchor = new ScoreBeamSource.Anchor(0, i);
            origins.add(Optional.of(anchor));
            columns.add(new ScoreBeamSource.Column(List.of(anchor)));
        }
        return new ScorePageInterpretation(List.of(bar), notes, 0, List.of(), List.of(), List.of(), List.of())
                .withBeamSource(new ScoreBeamSource(List.of(new ScoreBeamSource.Group(
                        "original-quartet", columns, bridges, List.of())), List.of(), origins));
    }

    private static int headX(int i) {
        return 39 + 51 * i;
    }

    private static int headY(int i) {
        return 103 - 7 * i;
    }

    private static byte[] raster(boolean continuous) {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = 49; y <= 113; y += 16) fill(ink, 15, 231, y, y, 0);
        for (int i = 0; i < 4; i++) {
            int x = headX(i), y = headY(i);
            fill(ink, x - 7, x + 7, y - 4, y + 4, 0);
            fill(ink, x + 8, x + 10, 28, y, 0);
        }
        fill(ink, headX(0) + 8, headX(3) + 10, 26, 32, 0);
        fill(ink, headX(0) + 8, headX(3) + 10, 40, 46, 0);
        if (!continuous) fill(ink, 121, 144, 40, 46, 255);
        return ink;
    }

    private static void fill(byte[] raster, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(raster, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
