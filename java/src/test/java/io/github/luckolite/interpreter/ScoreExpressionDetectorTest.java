// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.luckolite.interpreter.ScoreExpressiveEvent.*;

public final class ScoreExpressionDetectorTest {
    @Test
    public void tempoReturnAtARepeatedSilentRectangleUsesItsFirstBarAndOwnPage() {
        var silent = new MeasureRegion(.20f, .62f, .25f, .65f);
        var score = new ScorePageInterpretation(List.of(silent, silent, silent, silent),
                List.of(), 1, List.of(), List.of());
        var word = new PlayingTechniqueDetector.Word("a tempo", .21f, .20f, .32f, .28f);
        var events = ScoreExpressionDetector.apply(score, List.of(word),
                List.of(new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 1)), rails(), W, H)
                .expressiveEvents();
        assertEquals(1, events.size());
        var event = events.get(0);
        assertEquals(new ScoreAnchor(0, 0), event.start().orElseThrow());
        assertEquals(Scope.SCORE, event.scope());
        var first = ScoreDynamicContinuation.offsetEvidence(event, 10, 1);
        var second = ScoreDynamicContinuation.offsetEvidence(event, 20, 2);
        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(2, second.evidence().get(0).pageIndex());
        assertEquals(new ScoreAnchor(20, 0), second.start().orElseThrow());
        assertTrue(second.targetEventId().isEmpty());
    }

    private static final int W = 500, H = 300;

    private static byte[] rails() {
        var gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y = 100; y <= 164; y += 16) for (int x = 20; x < 460; x++) gray[y * W + x] = 0;
        return gray;
    }

    private static ScorePageInterpretation score(boolean meter) {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (float position : new float[] {.15f, .27f, .65f, .8f})
            notes.add(new ScoreNoteEvent(0, position, 0, 0, 1, 164f / H, false, 0, 0, 0, 1, 1, 0));
        return new ScorePageInterpretation(
                List.of(new MeasureRegion(.04f, .92f, .25f, .65f)),
                notes,
                1,
                List.of(),
                List.of(),
                meter ? List.of(new ScoreMeterChange(0, 4, 4)) : List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static ScorePageInterpretation detect(String text, boolean meter, byte[] gray) {
        float x = .04f + .27f * (.92f - .04f);
        return ScoreExpressionDetector.apply(
                score(meter),
                List.of(new PlayingTechniqueDetector.Word(text, x, 195f / H, x + .08f, 215f / H)),
                List.of(new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 1)),
                gray,
                W,
                H);
    }

    @Test
    public void unevenEngravingAttachesToWrittenBeat() {
        var result = detect("rit.", true, rails());
        assertEquals(1, result.expressiveEvents().size());
        var event = result.expressiveEvents().get(0);
        assertEquals(Kind.RITARDANDO, event.kind());
        assertEquals(new ScoreAnchor(0, 1), event.start().orElseThrow());
        assertEquals(score(true).notes(), result.notes());
    }

    @Test
    public void continuationKeepsUnresolvedEvidenceUntilMeterKnown() {
        var unresolved = detect("rall.", false, rails());
        assertEquals(Scope.UNRESOLVED, unresolved.expressiveEvents().get(0).scope());
        var resolved = ScoreExpressionDetector.resolve(unresolved, 4);
        assertEquals(
                new ScoreAnchor(0, 1), resolved.expressiveEvents().get(0).start().orElseThrow());
        var offset =
                ScoreDynamicContinuation.offsetEvidence(unresolved.expressiveEvents().get(0), 3, 2);
        assertTrue(offset.targetEventId().orElseThrow().startsWith("expression-column:3:"));
        assertEquals(2, offset.evidence().get(0).pageIndex());
    }

    @Test
    public void breathOwnsReleaseAndSforzandoOwnsAttack() {
        var breath = detect("breath", true, rails()).expressiveEvents().get(0);
        assertEquals(new ScoreAnchor(0, 2), breath.start().orElseThrow());
        var sfz = detect("sfz", true, rails()).expressiveEvents().get(0);
        assertEquals(Kind.SFORZATO, sfz.kind());
        assertEquals(Scope.NOTE, sfz.scope());
        assertEquals(new ScoreAnchor(0, 1), sfz.start().orElseThrow());
    }

    @Test
    public void textWithoutPrintedRailsCannotChangePlayback() {
        var empty = new byte[W * H];
        Arrays.fill(empty, (byte) 255);
        assertTrue(detect("rit.", true, empty).expressiveEvents().isEmpty());
        assertTrue(detect("written prose", true, rails()).expressiveEvents().isEmpty());
    }

    @Test
    public void aliasesAreTokenBoundedAndKeepRitenutoDistinct() {
        for (String text : List.of("rite", "rite.", "riten.", "ritenuto"))
            assertEquals(Kind.RITENUTO, ExpressiveDirectionText.parse(text).get(0).kind());
        assertEquals(Kind.RITARDANDO, ExpressiveDirectionText.parse("rit.").get(0).kind());
        assertTrue(ExpressiveDirectionText.parse("writer and ritual").isEmpty());
    }

    @Test
    public void resolvedColumnReuseKeepsPublicLookupAndUnownedEvidence() {
        var detected = detect("sfz", true, rails());
        var event = detected.expressiveEvents().get(0);
        assertEquals(List.of(1), ScoreExpressionDetector.targetIndices(detected, event));
        var foreign =
                new ScoreExpressiveEvent(
                        "foreign-expression",
                        event.kind(),
                        Optional.empty(),
                        Optional.empty(),
                        Scope.UNRESOLVED,
                        event.staffIndex(),
                        event.staffCount(),
                        event.targetEventId(),
                        event.strength(),
                        event.qualifierText(),
                        List.of(new Evidence("synthetic-manual", 0, .27f, 0, 1, "sfz")));
        var malformed =
                new ScoreExpressiveEvent(
                        "malformed-expression",
                        event.kind(),
                        Optional.empty(),
                        Optional.empty(),
                        Scope.UNRESOLVED,
                        event.staffIndex(),
                        event.staffCount(),
                        Optional.of("expression-column:0:0:1:not-an-int"),
                        event.strength(),
                        event.qualifierText(),
                        event.evidence());
        var inputEvents = List.of(event, foreign, malformed);
        var input = detected.withExpressiveEvents(inputEvents);
        var resolved = ScoreExpressionDetector.resolve(input, 4);
        assertEquals(event, resolved.expressiveEvents().get(0));
        assertSame(foreign, resolved.expressiveEvents().get(1));
        assertSame(malformed, resolved.expressiveEvents().get(2));
        assertEquals(List.of(1), ScoreExpressionDetector.targetIndices(resolved, event));
        assertTrue(ScoreExpressionDetector.targetIndices(resolved, foreign).isEmpty());
        assertTrue(ScoreExpressionDetector.targetIndices(resolved, malformed).isEmpty());
        assertEquals(inputEvents, input.expressiveEvents());
        assertEquals(input.notes(), resolved.notes());
    }
    @Test
    public void tempoDirectionUsesCommonOnsetWithDifferentReleases() {
        var notes = List.of(
                new ScoreNoteEvent(0, .27f, 2, 0, 1, .42f, false, 0, 0, 0, 2f),
                new ScoreNoteEvent(0, .27f, 0, 0, 1, .54f, false, 0, 0, 0, .5f));
        var score = new ScorePageInterpretation(List.of(new MeasureRegion(.04f, .92f, .25f, .65f)),
                notes, 1, List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)),
                List.of(), List.of(), List.of());
        var raw = detect("rit.", false, rails()).expressiveEvents();
        var result = ScoreExpressionDetector.resolve(score.withExpressiveEvents(raw), 4);
        assertEquals(1, result.expressiveEvents().size());
        var event = result.expressiveEvents().get(0);
        assertEquals(Scope.SCORE, event.scope());
        assertEquals(new ScoreAnchor(0, 1), event.start().orElseThrow());
        assertEquals(List.of(0, 1), ScoreExpressionDetector.targetIndices(result, event));
    }


    @Test
    public void standaloneTempoWordRequiresAStaffNearTheWrittenBarStart() {
        var staff = new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 1);
        var tempo = new PlayingTechniqueDetector.Word("Presto", .12f, .24f, .25f, .31f);
        var title = new PlayingTechniqueDetector.Word("Adagio", .60f, .02f, .72f, .09f);
        var result = ScoreExpressionDetector.apply(
                score(false), List.of(tempo, title), List.of(staff), rails(), W, H);
        assertEquals(1, result.expressiveEvents().size());
        var event = result.expressiveEvents().get(0);
        assertEquals(Kind.TEMPO_MARKING, event.kind());
        assertEquals("presto", event.qualifierText());
        assertEquals(new ScoreAnchor(0, 0), event.start().orElseThrow());
        assertEquals(Scope.SCORE, event.scope());
    }


    @Test
    public void fullSlowdownCaptionOwnsTheWordAndItsMildStrength() {
        var staff = new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 1);
        var whole = new PlayingTechniqueDetector.Word("Slightly slower", .10f, .24f, .34f, .31f);
        var fragment = new PlayingTechniqueDetector.Word("slower", .20f, .25f, .33f, .31f);
        var result = ScoreExpressionDetector.apply(
                score(true), List.of(fragment, whole), List.of(staff), rails(), W, H);
        assertEquals(1, result.expressiveEvents().size());
        var event = result.expressiveEvents().get(0);
        assertEquals(Kind.RITENUTO, event.kind());
        assertEquals(Strength.POCO, event.strength());
        assertEquals("Slightly slower", event.evidence().get(0).printedText());
    }

    @Test
    public void midBarTempoOverAnUpperRestCanOwnAnAlignedLowerAttack() {
        byte[] gray = rails();
        for (int y = 194; y <= 258; y += 16)
            Arrays.fill(gray, y * W + 20, y * W + 460, (byte) 0);
        var notes = List.of(
                new ScoreNoteEvent(0, .15f, 0, 1, 2, .74f, false, 0, 0, 0, 2f),
                new ScoreNoteEvent(0, .555f, 0, 1, 2, .75f, false, 0, 0, 0, 2f));
        var bars = List.of(new MeasureRegion(.04f, .92f, .30f, .89f));
        var view = new ScorePageInterpretation(bars, notes, 1, List.of(), List.of(),
                List.of(new ScoreMeterChange(0, 4, 4)), List.of(), List.of(), List.of())
                .withRests(List.of(new ScoreRestEvent(0, .55f, .44f, .03f, 0, 2, 1)));
        var staves = List.of(new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 2),
                new PlayingTechniqueDetector.Staff(194, 258, 16, 1, 2));
        var word = new PlayingTechniqueDetector.Word("Lento", .524f, .25f, .63f, .31f);
        var result = ScoreExpressionDetector.apply(view, List.of(word), staves, gray, W, H);
        assertEquals(1, result.expressiveEvents().size());
        var event = result.expressiveEvents().get(0);
        assertEquals(Kind.TEMPO_MARKING, event.kind());
        assertEquals(List.of(1), ScoreExpressionDetector.targetIndices(result, event));
        assertEquals(new ScoreAnchor(0, 2), event.start().orElseThrow());
    }


    @Test
    public void rehearsalTempoNeedsInkSeparatingTheHeadingFromItsVerse() {
        var score = new ScorePageInterpretation(
                List.of(new MeasureRegion(.2f, .85f, .23f, .65f)), List.of(), 1, List.of(), List.of());
        var word = new PlayingTechniqueDetector.Word("D Largo Interlude", 58f/W, 65f/H, 223f/W, 85f/H);
        var staffs = List.of(new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 1));
        assertTrue(ScoreExpressionDetector.apply(score, List.of(word), staffs, rails(), W, H)
                .expressiveEvents().isEmpty());
        var gray = rails();
        for (int y = 68; y < 82; y++)
            for (int x = 60; x <= 210; x++)
                if (x <= 75 || (x >= 101 && x <= 130) || x >= 136) gray[y * W + x] = 0;
        var events = ScoreExpressionDetector.apply(score, List.of(word), staffs, gray, W, H).expressiveEvents();
        assertEquals(1, events.size());
        assertEquals(Kind.TEMPO_MARKING, events.get(0).kind());
        assertEquals(new ScoreAnchor(0, 0), events.get(0).start().orElseThrow());
        assertEquals("largo", events.get(0).qualifierText());
    }

    @Test
    public void clippedVerseWordCannotStealTheTempoAtABarline() {
        var score = new ScorePageInterpretation(
                List.of(new MeasureRegion(.2f, .85f, .23f, .65f)), List.of(), 1, List.of(), List.of());
        var fragment = new PlayingTechniqueDetector.Word("lento", .22f, .22f, .31f, .28f);
        var verse = new PlayingTechniqueDetector.Word("Aria Vento Al Mare", .12f, .20f, .52f, .29f);
        var staffs = List.of(new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 1));
        assertEquals(1, ScoreExpressionDetector.apply(score, List.of(fragment), staffs, rails(), W, H)
                .expressiveEvents().size());
        assertTrue(ScoreExpressionDetector.apply(score, List.of(fragment, verse), staffs, rails(), W, H)
                .expressiveEvents().isEmpty());
    }
}
