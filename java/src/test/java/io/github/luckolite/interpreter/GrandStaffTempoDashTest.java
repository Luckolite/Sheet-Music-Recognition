// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static io.github.luckolite.interpreter.ScoreExpressiveEvent.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public final class GrandStaffTempoDashTest {
    private static final int WIDTH = 1000, HEIGHT = 900;
    private static final float GAP = 17.25f;

    @Test
    public void roundedDashesContinueAcrossACompleteGrandStaff() {
        assertEquals(Optional.of(new ScoreAnchor(3, 0)), span(false, 0, true, true));
    }

    @Test
    public void refusesDisconnectedInkAndStaffLocalWords() {
        assertTrue(span(true, 0, true, true).isEmpty());
        assertTrue(span(false, 1, true, true).isEmpty());
        assertTrue(span(false, 0, false, true).isEmpty());
        assertTrue(span(false, 0, true, false).isEmpty());
    }

    private static Optional<ScoreAnchor> span(
            boolean missingRow, int owner, boolean above, boolean target) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        var staffs = new ArrayList<PlayingTechniqueDetector.Staff>();
        var regions = new ArrayList<MeasureRegion>();
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int system = 0; system < 3; system++) {
            int y = 125 + system * 240;
            int left = 100, right = system == 2 ? 450 : 940;
            regions.add(new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH,
                    (y - 36f) / HEIGHT, (y + 185f) / HEIGHT));
            for (int staff = 0; staff < 2; staff++) {
                int top = y + staff * 105;
                staffs.add(new PlayingTechniqueDetector.Staff(top, top + 4 * GAP, GAP, staff, 2));
                for (int rule = 0; rule < 5; rule++) {
                    int row = Math.round(top + rule * GAP);
                    Arrays.fill(gray, row * WIDTH + left, row * WIDTH + 940, (byte) 0);
                }
                notes.add(new ScoreNoteEvent(system, .98f, 0, staff, 2,
                        (top + 4 * GAP) / HEIGHT, false, 0, 0, 2, 1, 1));
            }
        }
        regions.add(new MeasureRegion(.45f, .94f, 569f / HEIGHT, 790f / HEIGHT));
        notes.add(new ScoreNoteEvent(3, .94f, 0, 0, 2, 674f / HEIGHT, false, 0, 0, 2, 1, 1));
        dashes(gray, 332, 15, above ? 89 : 214, 3);
        if (!missingRow) dashes(gray, 100, 21, 320, 4);
        dashes(gray, 100, 9, 560, 3);
        var word = new PlayingTechniqueDetector.Word("accel.", .25f, (above ? 80f : 206f) / HEIGHT,
                .329f, (above ? 97f : 220f) / HEIGHT);
        var event = new ScoreExpressiveEvent("gradual", Kind.ACCELERANDO,
                Optional.of(new ScoreAnchor(0, 0)), Optional.empty(), Scope.SCORE, owner, 2,
                Optional.of("expression-column:0:" + owner + ":2:" + Float.floatToIntBits(.98f)),
                Strength.UNSPECIFIED, "",
                List.of(new Evidence("printed-expression-word", 0, word.left(), owner, 2, word.text())));
        var score = new ScorePageInterpretation(regions, notes, 1, List.of(),
                target ? List.of(new ScoreTempoChange(3, 0, 114)) : List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of()).withExpressiveEvents(List.of(event));
        return PrintedTempoSpans.apply(score, List.of(word), staffs, gray, WIDTH, HEIGHT)
                .expressiveEvents().get(0).end();
    }

    private static void dashes(byte[] gray, int left, int count, int top, int height) {
        for (int dash = 0; dash < count; dash++) {
            int x = left + dash * 40;
            for (int row = top; row < top + height; row++)
                Arrays.fill(gray, row * WIDTH + x, row * WIDTH + x + 9, (byte) 0);
        }
    }
}
