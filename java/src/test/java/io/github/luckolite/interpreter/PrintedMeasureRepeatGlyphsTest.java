// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PrintedMeasureRepeatGlyphsTest {
    private static final int W = 350, H = 170, GAP = 16;
    private static final List<MeasureRegion> BARS = List.of(
            bar(12, 132), bar(142, 238), bar(248, 338));

    private static MeasureRegion bar(int left, int right) {
        return new MeasureRegion(left / (float) W, right / (float) W, .16f, .84f);
    }

    @Test public void bothDotsAndAscendingSlashOwnOnlyTheirMeasure() {
        assertEquals(List.of(1), detect(page(true, false, false), List.of()));
    }

    @Test public void oppositeSlashOneDotAndIndependentAttackAreExcluded() {
        assertTrue(detect(page(true, true, false), List.of()).isEmpty());
        assertTrue(detect(page(false, false, false), List.of()).isEmpty());
        assertTrue(detect(page(true, false, true), List.of()).isEmpty());
        var note = new ScoreNoteEvent(1,.86f,4,0,1,84f/H,false,0,0,2,1);
        assertTrue(detect(page(true, false, false), List.of(note)).isEmpty());
    }

    @Test public void repeatOwnsAContainedFalseEighthRestAndPreservesANeighboringFullRest() {
        var repeatedDot = new ScoreRestEvent(1, .63f, 84f/H, 23f/H, 0, 1, .5);
        var wholeRest = ScoreRestEvent.fullMeasure(2, .5f, 84f/H, 4f/H, 0, 1);
        var score = new ScorePageInterpretation(BARS, List.of()).withRests(List.of(repeatedDot, wholeRest));
        var result = PrintedMeasureRepeatGlyphs.apply(score, page(true, false, false), W, H, 0);

        assertEquals(1, result.playbackDirections().size());
        assertEquals(1, result.playbackDirections().get(0).measureBoundary());
        assertEquals(List.of(wholeRest), result.rests());
    }

    @Test public void separateOrDifferentStaffRestsStillVetoTheRepeat() {
        var separate = new ScoreRestEvent(1, .89f, 84f/H, 23f/H, 0, 1, .5);
        var tall = new ScoreRestEvent(1, .63f, 84f/H, 55f/H, 0, 1, .5);
        var otherStaff = new ScoreRestEvent(1, .63f, 84f/H, 23f/H, 1, 2, .5);
        var wholeRest = ScoreRestEvent.fullMeasure(1, .63f, 84f/H, 4f/H, 0, 1);
        for (var printed : List.of(separate, tall, otherStaff, wholeRest)) {
            var score = new ScorePageInterpretation(BARS, List.of()).withRests(List.of(printed));
            var result = PrintedMeasureRepeatGlyphs.apply(score, page(true, false, false), W, H, 0);
            assertTrue(result.playbackDirections().isEmpty());
            assertEquals(List.of(printed), result.rests());
        }
    }

    private static List<Integer> detect(byte[] gray, List<ScoreNoteEvent> notes) {
        return PrintedMeasureRepeatGlyphs.detect(gray,W,H,BARS,notes)
                .stream().map(PrintedMeasureRepeatGlyphs.Glyph::measure).toList();
    }

    private static byte[] page(boolean lowerDot, boolean backwards, boolean extra) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int row = 52; row <= 116; row += GAP)
            Arrays.fill(gray, row * W + 10, row * W + 341, (byte) 0);
        for (int dy = -15; dy <= 15; dy++) {
            int center = Math.round(190 + dy * (backwards ? .88f : -.88f));
            Arrays.fill(gray, (84 + dy) * W + center - 6, (84 + dy) * W + center + 7, (byte) 0);
        }
        dot(gray,177,76,4);
        if (lowerDot) dot(gray,203,92,4);
        if (extra) dot(gray,224,90,5);
        return gray;
    }

    private static void dot(byte[] gray, int cx, int cy, int radius) {
        for (int y=cy-radius; y<=cy+radius; y++)
            for (int x=cx-radius; x<=cx+radius; x++) {
                int dx=x-cx, dy=y-cy;
                if (dx*dx+dy*dy<=radius*radius) gray[y*W+x]=0;
            }
    }
}
