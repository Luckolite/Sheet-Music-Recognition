// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Authored cue-prefix pixels and text controls, independent of any published score. */
public final class PrintedCueSizeOriginalTest {
    static final int W = 640, H = 240;
    static final PlayingTechniqueDetector.Staff STAFF =
            new PlayingTechniqueDetector.Staff(100, 156, 14, 0, 1);
    static final MeasureRegion REGION = new MeasureRegion(.1f, .95f, .3f, .85f);

    record Example(
            ScorePageInterpretation score,
            byte[] gray,
            List<PlayingTechniqueDetector.Word> words) {}

    static PlayingTechniqueDetector.Word word(String s, int l, int r) {
        return new PlayingTechniqueDetector.Word(s, l / (float) W, 64f / H, r / (float) W, 82f / H);
    }

    static Example example(boolean small) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y = 100; y <= 156; y += 14) for (int x = 64; x < 608; x++) gray[y * W + x] = 0;
        int[] xs = {150, 210, 270, 370, 440};
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < xs.length; i++) {
            int rx = small && i < 3 ? 6 : 9, ry = 5;
            for (int y = 142 - ry; y <= 142 + ry; y++)
                for (int x = xs[i] - rx; x <= xs[i] + rx; x++)
                    if (Math.pow((x - xs[i]) / (double) rx, 2)
                                    + Math.pow((y - 142) / (double) ry, 2)
                            <= 1) gray[y * W + x] = 0;
            notes.add(
                    new ScoreNoteEvent(
                            0,
                            (xs[i] / (float) W - REGION.left()) / (REGION.right() - REGION.left()),
                            2,
                            0,
                            1,
                            142f / H,
                            false,
                            0,
                            1,
                            2,
                            0,
                            1,
                            0));
        }
        var score =
                new ScorePageInterpretation(
                        List.of(REGION),
                        notes,
                        1,
                        List.of(),
                        List.of(),
                        List.of(new ScoreMeterChange(0, 4, 4)));
        return new Example(score, gray, List.of(word("Guitar", 120, 190), word("Play", 330, 360)));
    }

    static List<PrintedCueSize.Passage> detect(Example e) {
        return PrintedCueSize.detect(e.score(), e.words(), List.of(STAFF), e.gray(), W, H);
    }

    @Test
    public void pairedInstructionsAndSizeTransitionOwnExactlyThreeHeads() {
        var e = example(true);
        var p = detect(e);
        assertEquals(1, p.size());
        assertEquals(List.of(0, 1, 2), p.get(0).notes());
        assertEquals(3, p.get(0).playOwner());
    }

    @Test
    public void styleApplicationDoesNotChangePitchRhythmOrOtherProperties() {
        var e = example(true);
        var out = PrintedCueSize.applySizes(e.score(), detect(e));
        for (int i = 0; i < out.notes().size(); i++) {
            var n = out.notes().get(i);
            assertEquals(
                    i < 3 ? ScoreNoteEvent.VisualSize.CUE : ScoreNoteEvent.VisualSize.FULL,
                    n.visualSize());
            assertEquals(
                    e.score().notes().get(i), n.withVisualSize(ScoreNoteEvent.VisualSize.FULL));
        }
        assertTrue(
                e.score().notes().stream()
                        .allMatch(n -> n.visualSize() == ScoreNoteEvent.VisualSize.FULL));
    }

    @Test
    public void instrumentVocabularyIsIndependentOfTheGuitarExample() {
        var e = example(true);
        var words = List.of(word("Flute", 120, 190), word("PLAY.", 330, 360));
        assertEquals(1, detect(new Example(e.score(), e.gray(), words)).size());
    }

    @Test
    public void ordinarySizeNotesDoNotBecomeCueNotesFromWordsAlone() {
        assertTrue(detect(example(false)).isEmpty());
    }

    @Test
    public void absentReturnInstructionCannotBoundACuePrefix() {
        var e = example(true);
        assertTrue(detect(new Example(e.score(), e.gray(), List.of(e.words().get(0)))).isEmpty());
    }

    @Test
    public void arbitraryProseDoesNotAuthorizeSmallerNotation() {
        var e = example(true);
        assertTrue(
                detect(
                                new Example(
                                        e.score(),
                                        e.gray(),
                                        List.of(
                                                word("the guitar part", 120, 190),
                                                e.words().get(1))))
                        .isEmpty());
    }

    @Test
    public void twoPossibleReturnInstructionsAbstain() {
        var e = example(true);
        var ws = new ArrayList<>(e.words());
        ws.add(word("Play", 405, 435));
        assertTrue(detect(new Example(e.score(), e.gray(), ws)).isEmpty());
    }

    @Test
    public void repeatedIdenticalOcrReadingDoesNotDuplicatePassage() {
        var e = example(true);
        var ws = new ArrayList<>(e.words());
        ws.addAll(e.words());
        assertEquals(1, detect(new Example(e.score(), e.gray(), ws)).size());
    }

    @Test
    public void gracePrefixIsNotReclassifiedAsAnOrdinaryCuePassage() {
        var e = example(true);
        var ns = new ArrayList<>(e.score().notes());
        ns.set(1, ns.get(1).withArticulations(NoteOrnament.GRACE));
        assertTrue(
                detect(
                                new Example(
                                        e.score().withNotes(ns, List.of(0, 1, 2, 3, 4)),
                                        e.gray(),
                                        e.words()))
                        .isEmpty());
    }

    @Test
    public void unpitchedPrefixDoesNotBorrowAPitchedCueClassification() {
        var e = example(true);
        var ns = new ArrayList<>(e.score().notes());
        ns.set(1, ns.get(1).withKind(ScoreNoteEvent.Kind.UNPITCHED));
        assertTrue(
                detect(
                                new Example(
                                        e.score().withNotes(ns, List.of(0, 1, 2, 3, 4)),
                                        e.gray(),
                                        e.words()))
                        .isEmpty());
    }

    @Test
    public void absentPixelWitnessDoesNotInferSizeFromMetadata() {
        var e = example(true);
        byte[] blank = new byte[W * H];
        Arrays.fill(blank, (byte) 255);
        assertTrue(detect(new Example(e.score(), blank, e.words())).isEmpty());
    }

    @Test
    public void malformedGrayBufferAbstains() {
        var e = example(true);
        assertTrue(detect(new Example(e.score(), new byte[10], e.words())).isEmpty());
    }

    static Example extended(boolean secondStroke, boolean entryRest, boolean small) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y = 100; y <= 156; y += 14)
            Arrays.fill(gray, y * W + 64, y * W + 608, (byte) 0);
        for (int y = 100; y <= 156; y++) {
            gray[y * W + 472] = 0;
            if (secondStroke) gray[y * W + 478] = 0;
        }
        var bars = List.of(new MeasureRegion(.10f, .31f, .3f, .85f),
                new MeasureRegion(.315f, .52f, .3f, .85f),
                new MeasureRegion(.525f, .738f, .3f, .85f),
                new MeasureRegion(.753f, .95f, .3f, .85f));
        int[] xs = {128, 235, 378, 530, 571};
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < xs.length; i++) {
            int rx = small && i < 3 ? 6 : 9;
            for (int dy = -5; dy <= 5; dy++)
                for (int dx = -rx; dx <= rx; dx++)
                    if (dx * dx / (double) (rx * rx) + dy * dy / 25.0 <= 1)
                        gray[(142 + dy) * W + xs[i] + dx] = 0;
            int bar = Math.min(i, 3);
            var region = bars.get(bar);
            notes.add(new ScoreNoteEvent(bar,
                    (xs[i] / (float) W - region.left()) / (region.right() - region.left()),
                    2, 0, 1, 142f / H, false, 0, 1, 2, 0, 1, 0));
        }
        var score = new ScorePageInterpretation(bars, notes, 1,
                List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)));
        if (entryRest)
            score = score.withRests(List.of(new ScoreRestEvent(3, .16f, .5f, .04f, 0, 1, 1)));
        return new Example(score, gray, List.of(word("Guitar", 104, 146), word("Play", 505, 547)));
    }

    @Test
    public void extendedOpeningCueNeedsPhysicalBreakAndEntryRest() {
        var detected = detect(extended(true, true, true));
        assertEquals(1, detected.size());
        assertEquals(List.of(0, 1, 2), detected.get(0).notes());
        assertEquals(3, detected.get(0).playOwner());
        assertTrue(detect(extended(false, true, true)).isEmpty());
        assertTrue(detect(extended(true, false, true)).isEmpty());
        assertTrue(detect(extended(true, true, false)).isEmpty());
    }


    private static Example promptEntrance(boolean doubleBar, boolean small, boolean looseCaption,
            boolean obscuredCaption) {
        var e = extended(doubleBar, false, small);
        var notes = new ArrayList<>(e.score().notes().subList(0, 3));
        byte[] gray = e.gray();
        for (int y = 136; y < 149; y++) {
            if (y == 142) continue;
            Arrays.fill(gray, y * W + 490, y * W + 600, (byte) 255);
        }
        var entrance = e.score().measures().get(3);
        for (int x : new int[] {509, 550, 587}) {
            boolean hollow = x == 509;
            for (int dy = -5; dy <= 5; dy++)
                for (int dx = -9; dx <= 9; dx++) {
                    double distance = dx * dx / 81.0 + dy * dy / 25.0;
                    if (distance <= 1 && (!hollow || distance > .48))
                        gray[(142 + dy) * W + x + dx] = 0;
                }
            notes.add(new ScoreNoteEvent(3,
                    (x / (float) W - entrance.left()) / (entrance.right() - entrance.left()),
                    2, 0, 1, 142f / H, false, 0, hollow ? 0 : 1, 2, hollow ? 2 : 0, 1, 0));
        }
        var score = new ScorePageInterpretation(e.score().measures(), notes, 1,
                List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)));
        var caption = word("Oboe", 104, 146);
        if (looseCaption) {
            caption = new PlayingTechniqueDetector.Word(
                    caption.text(), caption.left(), 69f / H, caption.right(), 102f / H);
            for (int y = 76; y <= (obscuredCaption ? 98 : 93); y++)
                for (int x : new int[] {111, 118, 120, 125, 135, 142}) gray[y * W + x] = 0;
        }
        return new Example(score, gray, List.of(caption, word("Play", 499, 537)));
    }

    @Test
    public void directOpeningCueRetainsPrintedHeadsAndMutesOnlyTheCuePrefix() {
        var e = promptEntrance(true, true, true, false);
        var passages = detect(e);
        assertEquals(1, passages.size());
        assertEquals(List.of(0, 1, 2), passages.get(0).notes());
        assertEquals(3, passages.get(0).playOwner());
        assertTrue(passages.get(0).extendedOpening());
        var score = PrintedCueSize.apply(e.score(), passages);
        assertEquals(List.of(0, 1, 2), PrintedCueSize.mutedOpeningNotes(
                score.notes(), score.expressiveEvents(), 4).stream().boxed().toList());
        for (int i = 0; i < score.notes().size(); i++)
            assertEquals(e.score().notes().get(i),
                    score.notes().get(i).withVisualSize(ScoreNoteEvent.VisualSize.FULL));
    }

    @Test
    public void exactPlayCaptionCanDisownAnEnclosedEighthRestAndItsCachedDelay() {
        var e = promptEntrance(true, true, false, false);
        var notes = new ArrayList<>(e.score().notes());
        notes.set(3, notes.get(3).withLeadingRest(.5f));
        var score = e.score().withNotes(notes, List.of(0, 1, 2, 3, 4, 5));
        var rest = new ScoreRestEvent(3, .18f, 72f / H, 8f / H, 0, 1, .5);
        var falseRest = score.withRests(List.of(rest));
        var r = PrintedCueSize.apply(falseRest, e.words(), List.of(STAFF), e.gray(), W, H);
        assertTrue(r.rests().isEmpty());
        assertEquals(e.score().notes().get(3), r.notes().get(3));
        assertEquals(List.of(0, 1, 2), PrintedCueSize.mutedOpeningNotes(
                r.notes(), r.expressiveEvents(), 4).stream().boxed().toList());

        var genuineRest = new ScoreRestEvent(3, .18f, 126f / H, 8f / H, 0, 1, .5);
        var withActualRest = score.withRests(List.of(genuineRest));
        r = PrintedCueSize.apply(withActualRest, e.words(), List.of(STAFF), e.gray(), W, H);
        assertEquals(List.of(genuineRest), r.rests());
        assertEquals(.5f, r.notes().get(3).leadingRestBeats(), 0f);
    }

    @Test
    public void directOpeningCueRequiresPairedRailAndComparableHeadEvidence() {
        assertTrue(detect(promptEntrance(false, true, false, false)).isEmpty());
        assertTrue(detect(promptEntrance(true, false, false, false)).isEmpty());
        assertTrue(detect(promptEntrance(true, true, true, true)).isEmpty());
        var e = promptEntrance(true, true, false, false);
        var fewerPrincipalHeads = new ScorePageInterpretation(e.score().measures(),
                e.score().notes().subList(0, 5), 1,
                List.of(), List.of(), List.of(new ScoreMeterChange(0, 4, 4)));
        assertTrue(detect(new Example(fewerPrincipalHeads, e.gray(), e.words())).isEmpty());
    }

    @Test
    public void onlyAnExtendedOpeningScopeCanMuteTheFullyProvedLane() {
        var longCue = extended(true, true, true);
        var extendedScore = PrintedCueSize.apply(longCue.score(), detect(longCue));
        var muted = PrintedCueSize.mutedOpeningNotes(
                extendedScore.notes(), extendedScore.expressiveEvents(), 4);
        assertEquals(List.of(0, 1, 2), muted.stream().boxed().toList());
        var changed = new ArrayList<>(extendedScore.notes());
        changed.set(1, changed.get(1).withVisualSize(ScoreNoteEvent.VisualSize.FULL));
        assertTrue(PrintedCueSize.mutedOpeningNotes(
                changed, extendedScore.expressiveEvents(), 4).isEmpty());

        var compact = example(true);
        var compactScore = PrintedCueSize.apply(compact.score(), detect(compact));
        assertTrue(PrintedCueSize.mutedOpeningNotes(
                compactScore.notes(), compactScore.expressiveEvents(), 1).isEmpty());
    }
}
