// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class CompactClosingSlurTest {
    private static final int W = 1200, H = 520, GAP = 15, TOP = 120, BOTTOM = 180;
    private static final int FIRST = 179, LAST = 270;
    private static final List<MeasureRegion> MEASURES = List.of(
            new MeasureRegion(136f / W, 215f / W, .19f, .4f),
            new MeasureRegion(225f / W, 315f / W, .19f, .4f));

    private static ScoreNoteEvent whole(int measure, int x, float y, int step) {
        var region = MEASURES.get(measure);
        return new ScoreNoteEvent(measure, (x / (float) W - region.left()) /
                (region.right() - region.left()), step, 0, 1, y / H, false, 0, 0,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 4, 1, 0).withClef(30);
    }

    private static void oval(byte[] pixels, int x, int y) {
        for (int dy = -5; dy <= 5; dy++)
            for (int dx = -9; dx <= 9; dx++) {
                double r = dx * dx / 81. + dy * dy / 25.;
                if (r >= .45 && r <= 1) pixels[(y + dy) * W + x + dx] = 0;
            }
    }

    private static void bar(byte[] pixels, int left, int right) {
        for (int y = TOP; y <= BOTTOM; y++)
            Arrays.fill(pixels, y * W + left, y * W + right + 1, (byte) 0);
    }

    private static ScoreSlurSource slurs(boolean divider, boolean close, boolean detached,
            boolean timed, boolean chord) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += GAP)
            Arrays.fill(gray, y * W + 95, y * W + 339, (byte) 0);
        oval(gray, FIRST, 150);
        oval(gray, LAST, 158);
        for (int x = FIRST; x <= LAST; x++) {
            if (detached && x >= 244 && x <= 250) continue;
            double t = (x - FIRST) / (double) (LAST - FIRST);
            int y = (int) Math.round(137 + 9 * t - 72 * t * (1 - t));
            gray[y * W + x] = 0;
            gray[(y + 1) * W + x] = 0;
        }
        if (divider) bar(gray, 219, 221);
        bar(gray, 313, 314);
        if (close) bar(gray, 330, 335);
        var notes = new java.util.ArrayList<>(
                List.of(whole(0, FIRST, 150, 4), whole(1, LAST, 157.5f, 3)));
        if (chord) notes.add(whole(1, LAST, 165, 2));
        var score = new ScorePageInterpretation(MEASURES, notes, 0,
                List.of(new ScoreKeyChange(0, 0)), List.of(),
                timed ? List.of(new ScoreMeterChange(0, 4, 4)) : List.of());
        return PrintedSlurRecognition.captureObserved(score, 2, gray, null, W, H,
                List.of(new PlayingTechniqueDetector.Staff(TOP, BOTTOM, GAP, 0, 1)))
                .orElseThrow().score().slurSource();
    }

    @Test public void geometryOwnsTheArcAcrossThePrintedDivider() {
        var result = slurs(true, true, false, false, false);
        assertTrue(result.spans().isEmpty());
        assertEquals(1, result.candidates().size());
        var span = result.candidates().get(0);
        assertEquals(new ScoreSlurPages.Anchor(2, 0), span.start());
        assertEquals(new ScoreSlurPages.Anchor(2, 1), span.end());
        assertEquals(ScoreSlurSpan.Placement.ABOVE, span.placement());
    }

    @Test public void explicitMeterClassifiesTheSameOwnersWithoutATie() {
        var result = slurs(true, true, false, true, false);
        assertTrue(result.candidates().isEmpty());
        assertEquals(1, result.spans().size());
        assertEquals(ScoreSlurSpan.LineType.SOLID, result.spans().get(0).lineType());
    }

    @Test public void closingEvidenceAndUniqueConnectedOwnersAreRequired() {
        assertTrue(slurs(false, true, false, false, false).candidates().isEmpty());
        assertTrue(slurs(true, false, false, false, false).candidates().isEmpty());
        assertTrue(slurs(true, true, true, false, false).candidates().isEmpty());
        assertTrue(slurs(true, true, false, false, true).candidates().isEmpty());
    }
}
