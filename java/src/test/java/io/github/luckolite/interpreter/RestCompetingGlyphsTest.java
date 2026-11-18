// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class RestCompetingGlyphsTest {
    private static final int WIDTH = 180, HEIGHT = 150;

    @Test
    public void isolatedThreeAndOneNeedTwoSeparatePrintedComponents() {
        var paper = new byte[WIDTH * HEIGHT];
        Arrays.fill(paper, (byte) 255);
        stripe(paper, 68, 43, 78, 45);
        stripe(paper, 76, 46, 80, 49);
        stripe(paper, 71, 50, 77, 52);
        stripe(paper, 76, 53, 80, 57);
        stripe(paper, 68, 58, 78, 61);
        stripe(paper, 71, 67, 76, 70);
        stripe(paper, 74, 71, 76, 81);
        stripe(paper, 70, 81, 78, 83);
        assertTrue(matches(paper));

        var joined = paper.clone();
        stripe(joined, 75, 61, 76, 67);
        assertFalse(matches(joined));

        var noThree = paper.clone();
        for (int row = 41; row <= 62; row++)
            Arrays.fill(noThree, row * WIDTH + 66, row * WIDTH + 81, (byte) 255);
        assertFalse(matches(noThree));
    }

    @Test
    public void detachedLedgerAndSlurNeedBothIndependentSourceWitnesses() {
        float x = 100, y = 105, gap = 16;
        var rest = new ScoreRestEvent(0, .5f, y / HEIGHT, 26f / HEIGHT, 0, 1, .5);
        var bar =
                new MeasureRegion(
                        25f / WIDTH, 175f / WIDTH, 8f / HEIGHT, (8 + 8.2f * gap) / HEIGHT, 0);
        var head =
                new ScoreNoteEvent(
                        0,
                        .5f,
                        2,
                        0,
                        1,
                        72f / HEIGHT,
                        false,
                        0,
                        2,
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                        0,
                        1,
                        0,
                        0,
                        ScoreNoteEvent.CLEF_TREBLE,
                        false,
                        0,
                        false,
                        0,
                        0,
                        1,
                        0,
                        ScoreNoteEvent.Kind.PITCHED,
                        ScoreNoteEvent.VisualSize.FULL);

        var arc = new byte[WIDTH * HEIGHT];
        Arrays.fill(arc, (byte) 255);
        stripe(arc, 110, 25, 112, 73);
        for (int xx = 61; xx <= 106; xx++) {
            float offset = (106 - xx) / 38f;
            int yy = 107 + Math.round(23 * offset - 4 * offset * offset);
            stripe(arc, xx, yy, xx, yy + 3);
        }
        var separate = arc.clone();
        stripe(separate, 90, 92, 110, 97);
        assertTrue(retained(rest, head, bar, separate, separate).isEmpty());
        assertEquals(List.of(rest), retained(rest, head, bar, separate, arc));

        var cut = separate.clone();
        for (int yy = 104; yy <= 131; yy++)
            Arrays.fill(cut, yy * WIDTH + 70, yy * WIDTH + 75, (byte) 255);
        assertEquals(List.of(rest), retained(rest, head, bar, separate, cut));

        var realHook = new byte[WIDTH * HEIGHT];
        Arrays.fill(realHook, (byte) 255);
        stripe(realHook, 110, 25, 112, 73);
        stripe(realHook, 93, 92, 99, 100);
        for (int yy = 93; yy <= 118; yy++) {
            int xx = 105 - Math.round((yy - 93) * .48f);
            stripe(realHook, xx, yy, xx + 2, yy);
        }
        stripe(realHook, 99, 95, 104, 97);
        assertEquals(List.of(rest), retained(rest, head, bar, realHook, realHook));
    }

    private static List<ScoreRestEvent> retained(
            ScoreRestEvent rest,
            ScoreNoteEvent head,
            MeasureRegion bar,
            byte[] gray,
            byte[] source) {
        return RestCompetingGlyphs.remove(
                List.of(rest),
                List.of(head),
                List.of(bar),
                new byte[WIDTH * HEIGHT],
                gray,
                source,
                WIDTH,
                HEIGHT);
    }

    private static boolean matches(byte[] paper) {
        return RestCompetingGlyphs.threeThenOne(paper, WIDTH, HEIGHT, 74, 64, 78, 16);
    }

    private static void stripe(byte[] paper, int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(paper, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
    }
}
