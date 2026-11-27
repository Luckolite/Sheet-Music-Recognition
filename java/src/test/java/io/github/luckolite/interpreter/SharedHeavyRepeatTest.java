// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SharedHeavyRepeatTest {
    private static final int W = 640, H = 200, TOP = 60, GAP = 16;
    private static final String DOCUMENT = "a".repeat(64);

    @Test
    public void equalHeavyRailsNeedFourDots() {
        var ink = new byte[W * H];
        Arrays.fill(ink, (byte) 255);
        for (int y = TOP; y <= TOP + 4 * GAP; y++) {
            Arrays.fill(ink, y * W + 287, y * W + 297, (byte) 0);
            Arrays.fill(ink, y * W + 302, y * W + 312, (byte) 0);
        }
        for (int line = 0; line < 5; line++) {
            int row = (TOP + line * GAP) * W;
            Arrays.fill(ink, row + 20, row + W - 20, (byte) 0);
        }
        for (int y : new int[]{84, 100}) dot(ink, 278, y);
        assertTrue(PrintedRepeatGlyphs.sharedRepeatCenters(ink, W, H, TOP, TOP + 4 * GAP, GAP).isEmpty());
        for (int y : new int[]{84, 100}) dot(ink, 322, y);

        assertEquals(List.of(299f), PrintedRepeatGlyphs.sharedRepeatCenters(
                ink, W, H, TOP, TOP + 4 * GAP, GAP));
        var bars = List.of(new MeasureRegion(.1f, 297f / W, .2f, .7f),
                new MeasureRegion(302f / W, .9f, .2f, .7f));
        var marks = PrintedRepeatGlyphs.detect(ink, W, H,
                List.of(new PlayingTechniqueDetector.Staff(TOP, TOP + 4 * GAP, GAP, 0, 1)), bars);
        assertEquals(2, marks.size());
        assertTrue(marks.stream().anyMatch(m -> m.boundary() == 1 && m.outgoing()));
        assertTrue(marks.stream().anyMatch(m -> m.boundary() == 1 && !m.outgoing()));
    }

    @Test
    public void implicitOpeningSurvivesAnUnrelatedUnresolvedRepeat() {
        var close = closing(3, 1, true);
        var start = new ScorePlaybackDirection(1, ScorePlaybackDirection.Kind.REPEAT_START,
                new ScorePlaybackDirection.Details(0, "open", "", "", "printed-repeat:test", 2,
                        List.of(), Optional.empty(), ScorePlaybackDirection.AfterJumpRepeats.DEFAULT,
                        "", List.of(new ScoreExpressiveEvent.Evidence("open", 3, .5f, 0, 2, ""))));
        var second = page(3, 0, List.of(start), List.of(close));
        var unrelated = page(4, 0, List.of(), List.of(closing(4, 2, false)));
        var result = PrintedRepeatDocument.assemble(List.of(page(2, 0, List.of(), List.of()), second, unrelated));
        assertEquals(List.of(0, 5), result.directions().stream().map(ScorePlaybackDirection::measureBoundary).toList());
        assertEquals(java.util.Set.of(close.eventId()), result.resolvedIds());
        assertTrue(PrintedRepeatDocument.assemble(List.of(page(2, 20, List.of(), List.of()),
                second, unrelated)).directions().isEmpty());
        assertTrue(PrintedRepeatDocument.assemble(List.of(page(2, 0, List.of(), List.of()),
                page(3, 0, List.of(), List.of(close)), unrelated)).directions().isEmpty());
    }

    private static void dot(byte[] ink, int cx, int cy) {
        for (int y = cy - 3; y <= cy + 3; y++)
            for (int x = cx - 3; x <= cx + 3; x++)
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= 10) ink[y * W + x] = 0;
    }

    private static ScorePageInterpretation page(int physical, int first,
            List<ScorePlaybackDirection> directions, List<ScoreExpressiveEvent> events) {
        var score = new ScorePageInterpretation(
                Collections.nCopies(4, new MeasureRegion(.1f, .9f, .1f, .9f)), List.of(), first,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), directions, events);
        return score.withClockContextSource(new ScoreClockContextSource(
                new ScoreClockContextSource.Origin(DOCUMENT, physical, 6, W, H, "b".repeat(64), "c".repeat(64)),
                0, 4, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()));
    }

    private static ScoreExpressiveEvent closing(int page, int boundary, boolean corroborated) {
        String id = "unresolved-printed-repeat:" + page + ":" + boundary + ":true";
        String source = "printed-repeat-end:v1:" + id;
        var proof = new ScoreExpressiveEvent.Evidence(source, page, .5f, 0, 2, "");
        return new ScoreExpressiveEvent(id, ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                Optional.of(new ScoreAnchor(boundary, 0)), Optional.empty(),
                ScoreExpressiveEvent.Scope.UNRESOLVED, 0, 2, Optional.empty(),
                ScoreExpressiveEvent.Strength.UNSPECIFIED, "unresolved",
                corroborated ? List.of(proof, new ScoreExpressiveEvent.Evidence(
                        source, page, .5001f, 1, 2, "")) : List.of(proof));
    }
}
