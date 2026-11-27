// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class PrintedRepeatCaptionTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 300;
    private static final List<List<Integer>> PITCHES =
            List.of(List.of(30, 31, 29), List.of(27, 28, 29), List.of(33, 29), List.of(28, 26));
    private static final List<ScorePageTimeline.PageRange> ARRANGEMENTS =
            List.of(new ScorePageTimeline.PageRange(0, 1), new ScorePageTimeline.PageRange(1, 2));

    @Test
    public void exactEngravedPassageSuppliesTheNumberedEndings() {
        var result = resolve(captionPage(false), PITCHES);
        var rows = result.get(0).directions();
        assertEquals(4, rows.size());
        assertEquals(4, result.get(0).resolvedIds().size());
        var plan = ScoreNavigationPlan.create(4, rows);
        var visits = new ArrayList<Integer>();
        for (int i = 0; i < plan.measureCount(); i++) visits.add(plan.sourceMeasure(i));
        assertEquals(List.of(0, 1, 2, 0, 1, 2, 0, 1, 3), visits);
        assertTrue(rows.stream().allMatch(d -> d.details().evidence().size() == 2));
    }

    @Test
    public void incompleteCaptionsAndUnrelatedMelodyStayUnresolved() {
        assertTrue(resolve(captionPage(true), PITCHES).isEmpty());
        assertTrue(resolve(captionPage(false),
                List.of(PITCHES.get(0), List.of(27, 28, 30), PITCHES.get(2), PITCHES.get(3))).isEmpty());
    }

    @Test
    public void ignoresCaptionAwayFromItsBarAndOrdinaryProse() {
        var bar = new MeasureRegion(.1f, .3f, .28f, .70f);
        var staff = new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 1);
        var page = new ScorePageInterpretation(
                List.of(bar), List.of(), 1, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        var gray = new byte[WIDTH * HEIGHT];
        java.util.Arrays.fill(gray, (byte) 255);
        var annotated = PrintedRepeatNavigation.apply(page, List.of(
                new PlayingTechniqueDetector.Word("The Ending", .104f, .2f, .2f, .25f),
                new PlayingTechniqueDetector.Word("Ending", .157f, .2f, .22f, .25f),
                new PlayingTechniqueDetector.Word("Repeat Start", .104f, .2f, .24f, .25f)),
                List.of(staff), gray, WIDTH, HEIGHT, 0);
        assertEquals(List.of("Repeat Start"), annotated.expressiveEvents().stream()
                .map(ScoreExpressiveEvent::qualifierText).toList());
        assertTrue(annotated.playbackDirections().isEmpty());
    }

    private static List<PrintedRepeatDocument.Result> resolve(
            ScorePageInterpretation target, List<List<Integer>> targetPitches) {
        var donor = page(List.of(), List.of(
                direction(0, ScorePlaybackDirection.Kind.REPEAT_START, "", List.of(), -1),
                direction(3, ScorePlaybackDirection.Kind.REPEAT_END, "", List.of(), -1),
                direction(2, ScorePlaybackDirection.Kind.ENDING, "1., 2.", List.of(1, 2), 3),
                direction(3, ScorePlaybackDirection.Kind.ENDING, "3.", List.of(3), 4)));
        var projected = PrintedRepeatDocument.resolveCaptionRoutes(
                List.of(donor, target), ARRANGEMENTS, page -> page == 0 ? PITCHES : targetPitches);
        if (projected.isEmpty()) return List.of();
        return List.of(PrintedRepeatDocument.validatedCaptionPlayback(projected,
                ARRANGEMENTS.get(1), new int[] {0, 0}, List.of(), 4, new ScoreMeterMap(3, List.of())));
    }

    private static ScorePageInterpretation captionPage(boolean omitFirstEnding) {
        var events = new ArrayList<>(List.of(caption(0, "Repeat Start"),
                caption(3, "Repeat End"), caption(3, "Ending")));
        if (!omitFirstEnding) events.add(caption(2, "Ending"));
        return page(events, List.of());
    }

    private static ScorePageInterpretation page(
            List<ScoreExpressiveEvent> events, List<ScorePlaybackDirection> directions) {
        return new ScorePageInterpretation(
                Collections.nCopies(4, new MeasureRegion(.1f, .9f, .1f, .9f)), List.of(), 1,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), directions, events);
    }

    private static ScoreExpressiveEvent caption(int bar, String text) {
        String id = PrintedRepeatNavigation.CAPTION_ID + "1:" + text + ":" + bar;
        return new ScoreExpressiveEvent(id, ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                Optional.of(new ScoreAnchor(bar, 0)), Optional.empty(), ScoreExpressiveEvent.Scope.UNRESOLVED,
                0, 1, Optional.empty(), ScoreExpressiveEvent.Strength.UNSPECIFIED, text,
                List.of(new ScoreExpressiveEvent.Evidence(
                        PrintedRepeatNavigation.CAPTION_SOURCE + id, 1, .2f, 0, 1, text)));
    }

    private static ScorePlaybackDirection direction(
            int bar, ScorePlaybackDirection.Kind kind, String text, List<Integer> passes, int end) {
        String id = "printed-repeat:0:0:3:" + kind + ":" + bar;
        return new ScorePlaybackDirection(bar, kind, new ScorePlaybackDirection.Details(
                0, id, "", "", "printed-repeat:0:0:3", 3, passes,
                end < 0 ? Optional.empty() : Optional.of(new ScoreAnchor(end, 0)),
                ScorePlaybackDirection.AfterJumpRepeats.DEFAULT, text,
                List.of(new ScoreExpressiveEvent.Evidence(id, 0, .2f, 0, 1, text))));
    }
}
