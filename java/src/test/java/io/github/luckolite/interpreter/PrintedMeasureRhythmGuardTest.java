// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrintedMeasureRhythmGuardTest {
    @Test
    public void preservesDoubleRuleInsidePaddedSingleStaff() {
        byte[] gray = paper();
        for (int y = 320; y <= 376; y += 14)
            Arrays.fill(gray, y * 1000 + 100, y * 1000 + 901, (byte) 0);
        for (int y = 320; y <= 376; y++) {
            gray[y * 1000 + 496] = 0;
            gray[y * 1000 + 504] = 0;
        }
        var left = new MeasureRegion(.1f, .49f, .35f, .54f);
        var right = new MeasureRegion(.51f, .9f, .35f, .54f);
        var joined = new MeasureRegion(.1f, .9f, .35f, .54f);
        assertEquals(List.of(left, right), PrintedMeasureRhythmGuard.preservePrintedBoundaries(
                List.of(left, right), List.of(joined), gray, 1000, 800));
        for (int y = 310; y <= 386; y++)
            Arrays.fill(gray, y * 1000 + 496, y * 1000 + 505, (byte) 0);
        assertEquals(List.of(joined), PrintedMeasureRhythmGuard.preservePrintedBoundaries(
                List.of(left, right), List.of(joined), gray, 1000, 800));
    }

    @Test
    public void ledgerExtensionCannotBecomeAnOverlappingStaffRow() {
        var upper =
                List.of(
                        new MeasureRegion(.16f, .38f, .068f, .121f),
                        new MeasureRegion(.39f, .61f, .068f, .121f),
                        new MeasureRegion(.62f, .71f, .068f, .121f),
                        new MeasureRegion(.72f, .91f, .068f, .121f));
        var phantom = new MeasureRegion(.319f, .791f, .10f, .152f);
        var next = new MeasureRegion(.16f, .9f, .214f, .266f);
        var regions = new ArrayList<>(upper);
        regions.add(phantom);
        regions.add(next);
        var expected = new ArrayList<>(upper);
        expected.add(next);
        assertEquals(expected, PrintedMeasureRhythmGuard.rejectOverlappingRows(regions));
    }

    @Test
    public void neighboringPrintedStaffRowsRemainSeparate() {
        var upper =
                List.of(
                        new MeasureRegion(.16f, .38f, .07f, .12f),
                        new MeasureRegion(.39f, .61f, .07f, .12f),
                        new MeasureRegion(.62f, .9f, .07f, .12f));
        var lower = new MeasureRegion(.16f, .9f, .16f, .21f);
        var regions = new ArrayList<>(upper);
        regions.add(lower);
        assertEquals(regions, PrintedMeasureRhythmGuard.rejectOverlappingRows(regions));
    }

    @Test
    public void separateDuetBarlinesSurviveWhitespaceBetweenStaffs() {
        byte[] g = paper();
        for (int top : new int[] {180, 400}) {
            for (int line = 0; line < 5; line++)
                for (int x = 100; x <= 900; x++) g[(top + line * 14) * 1000 + x] = 0;
            for (int y = top; y <= top + 56; y++) {
                g[y * 1000 + 305] = 0;
                g[y * 1000 + 505] = 0;
            }
        }
        assertEquals(
                raw,
                PrintedMeasureRhythmGuard.preservePrintedBoundaries(
                        raw, List.of(new MeasureRegion(.1f, .9f, .2f, .6f)), g, 1000, 800));
    }

    @Test
    public void footerDigitsCannotCreateAnEmptyExtraSystem() {
        var extra = new MeasureRegion(.1f, .9f, .75f, .95f);
        var proposed = new ArrayList<>(raw);
        proposed.add(extra);
        assertEquals(
                raw,
                PrintedMeasureRhythmGuard.rejectUnprintedRows(
                        raw, proposed, new byte[800000], paper(), 1000, 800));
    }

    @Test
    public void unsupportedOcrRowCannotBorrowInkFromEitherAdjacentStaff() {
        var upper = new MeasureRegion(.1f, .9f, .16f, .34f);
        var lower = new MeasureRegion(.1f, .9f, .46f, .64f);
        var generated = new MeasureRegion(.1f, .9f, .31f, .52f);
        var detected = List.of(upper, lower);
        var proposed = List.of(upper, generated, lower);
        byte[] gray = paper();
        byte[] labels = new byte[gray.length];
        for (int top : new int[] {170, 397})
            for (int line = 0; line < 5; line++) {
                int y = (top + line * 12) * 1000;
                for (int weight = 0; weight < 2; weight++) {
                    Arrays.fill(gray, y + weight * 1000 + 100, y + weight * 1000 + 900, (byte) 0);
                    Arrays.fill(
                            labels,
                            y + weight * 1000 + 100,
                            y + weight * 1000 + 900,
                            OmrMeasurePostProcessor.STAFF);
                }
            }
        for (int guide : new int[] {290, 316})
            Arrays.fill(gray, guide * 1000 + 100, guide * 1000 + 900, (byte) 0);
        var rejectedAnchorTops = new HashSet<Float>();
        assertEquals(
                detected,
                PrintedMeasureRhythmGuard.rejectUnprintedRows(
                        detected, proposed, labels, gray, 1000, 800, rejectedAnchorTops));
        assertEquals(Set.of(generated.top()), rejectedAnchorTops);
    }

    @Test
    public void recoveredPrintedStaffMayOverlapNeighboringMeasurePadding() {
        var upper = new MeasureRegion(.1f, .9f, .13f, .31f);
        var lower = new MeasureRegion(.1f, .9f, .49f, .67f);
        var generated = new MeasureRegion(.1f, .9f, .29f, .51f);
        var detected = List.of(upper, lower);
        var proposed = List.of(upper, generated, lower);
        byte[] gray = paper();
        for (int top : new int[] {145, 294, 417})
            for (int line = 0; line < 5; line++) {
                int y = (top + line * 12) * 1000;
                Arrays.fill(gray, y + 100, y + 900, (byte) 0);
            }
        var rejectedAnchorTops = new HashSet<Float>();
        assertEquals(
                proposed,
                PrintedMeasureRhythmGuard.rejectUnprintedRows(
                        detected,
                        proposed,
                        new byte[gray.length],
                        gray,
                        1000,
                        800,
                        rejectedAnchorTops));
        assertTrue(rejectedAnchorTops.isEmpty());
    }

    @Test
    public void printedStaffSurvivesEvenIfItsSemanticLabelsAreMissing() {
        var extra = new MeasureRegion(.1f, .9f, .75f, .95f);
        var proposed = new ArrayList<>(raw);
        proposed.add(extra);
        byte[] g = paper();
        for (int line = 0; line < 5; line++)
            for (int x = 100; x < 900; x++) g[(620 + line * 10) * 1000 + x] = 0;
        assertEquals(
                proposed,
                PrintedMeasureRhythmGuard.rejectUnprintedRows(
                        raw, proposed, new byte[800000], g, 1000, 800));
    }

    @Test
    public void wrongOcrCountCannotCompressThreePrintedBarsIntoOne() {
        byte[] g = paper();
        for (int y = 160; y <= 480; y++) {
            g[y * 1000 + 305] = 0;
            g[y * 1000 + 505] = 0;
        }
        var merged = List.of(new MeasureRegion(.1f, .9f, .2f, .6f));
        assertEquals(
                raw,
                PrintedMeasureRhythmGuard.preservePrintedBoundaries(raw, merged, g, 1000, 800));
    }

    @Test
    public void shortStemsDoNotProtectFalseBoundaries() {
        byte[] g = paper();
        for (int y = 200; y <= 260; y++) {
            g[y * 1000 + 305] = 0;
            g[y * 1000 + 505] = 0;
        }
        var merged = List.of(new MeasureRegion(.1f, .9f, .2f, .6f));
        assertEquals(
                merged,
                PrintedMeasureRhythmGuard.preservePrintedBoundaries(raw, merged, g, 1000, 800));
        assertEquals(
                merged,
                PrintedMeasureRhythmGuard.preservePrintedBoundaries(raw, merged, null, 1000, 800));
    }

    private final List<MeasureRegion> raw =
            List.of(
                    new MeasureRegion(.1f, .3f, .2f, .6f),
                    new MeasureRegion(.31f, .5f, .2f, .6f),
                    new MeasureRegion(.51f, .9f, .2f, .6f));
    private final List<MeasureRegion> fitted =
            List.of(
                    raw.get(0),
                    raw.get(1),
                    new MeasureRegion(.51f, .703f, .2f, .6f),
                    new MeasureRegion(.707f, .9f, .2f, .6f));

    private byte[] paper() {
        byte[] g = new byte[1000 * 800];
        Arrays.fill(g, (byte) 255);
        return g;
    }

    private List<ScoreNoteEvent> notes(int runBeams, boolean bass, int neighborBeams) {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int m = 0; m < 3; m++) {
            int n = m == 2 ? 16 : 8;
            for (int i = 0; i < n; i++)
                notes.add(
                        new ScoreNoteEvent(
                                m,
                                .03f + i * .94f / n,
                                i % 7,
                                0,
                                2,
                                .3f,
                                false,
                                0,
                                m == 2 ? runBeams : neighborBeams,
                                2,
                                0));
            if (bass)
                for (int i = 0; i < 2; i++)
                    notes.add(
                            new ScoreNoteEvent(m, .03f + i * .5f, 0, 1, 2, .5f, false, 0, 0, 2, 2));
        }
        return notes;
    }

    @Test
    public void completeDenseMeasureSurvivesIncorrectPrintedCount() {
        assertEquals(
                raw,
                PrintedMeasureRhythmGuard.preserveCompleteRuns(
                        raw, fitted, notes(2, true, 1), paper(), 1000, 800));
    }

    @Test
    public void genuinelyMergedEightBeatRunStillSplits() {
        assertEquals(
                fitted,
                PrintedMeasureRhythmGuard.preserveCompleteRuns(
                        raw, fitted, notes(1, true, 1), paper(), 1000, 800));
    }

    @Test
    public void missingAccompanimentCannotOverruleNumbering() {
        assertEquals(
                fitted,
                PrintedMeasureRhythmGuard.preserveCompleteRuns(
                        raw, fitted, notes(2, false, 1), paper(), 1000, 800));
    }

    @Test
    public void differentRhythmOnNeighboringBarsIsNotEnoughEvidence() {
        assertEquals(
                fitted,
                PrintedMeasureRhythmGuard.preserveCompleteRuns(
                        raw,
                        fitted,
                        notes(2, true, 2).stream()
                                .filter(n -> n.measureIndex() == 2 || n.staffIndex() == 0)
                                .toList(),
                        paper(),
                        1000,
                        800));
    }

    @Test
    public void visibleBarlineAlwaysSurvivesEvenWhenRhythmIsUnderread() {
        byte[] g = paper();
        for (int y = 160; y <= 480; y++) g[y * 1000 + 705] = 0;
        assertEquals(
                fitted,
                PrintedMeasureRhythmGuard.preserveCompleteRuns(
                        raw, fitted, notes(2, true, 1), g, 1000, 800));
    }

    @Test
    public void missingPixelEvidenceDoesNotSuppressASeparator() {
        assertEquals(
                fitted,
                PrintedMeasureRhythmGuard.preserveCompleteRuns(
                        raw, fitted, notes(2, true, 1), null, 1000, 800));
    }

    @Test
    public void oneUnresolvedStemKeepsAnInlineTripleRunWhenThePrintedShaftClosesTheOctave()
            throws Exception {
        var plate = new InlineTripleRun();
        var notes = plate.notes(1);
        assertEquals(plate.raw, plate.preserve(notes));
        assertEquals(0, notes.get(8).stemDirection());
        assertEquals(plate.fitted, plate.preserve(plate.notes(2)));
        plate.ink(375, 377, 94, 113, 255);
        assertEquals(plate.fitted, plate.preserve(notes));
    }

    @Test
    public void theSharedShaftStillRequiresAnInlineTripleAndPrintedClosingBarline() throws Exception {
        var incorrectMeter = new InlineTripleRun('2');
        assertEquals(incorrectMeter.fitted, incorrectMeter.preserve(incorrectMeter.notes(1)));

        var missingBarline = new InlineTripleRun();
        missingBarline.ink(756, 760, 54, 118, 255);
        assertEquals(missingBarline.fitted, missingBarline.preserve(missingBarline.notes(1)));
    }

    private static final class InlineTripleRun {
        private static final int WIDTH = 800, HEIGHT = 180, TOP = 54, GAP = 16;
        private final byte[] gray = new byte[WIDTH * HEIGHT];
        private final byte[] labels = new byte[gray.length];
        private final List<MeasureRegion> raw =
                List.of(region(44, 101), region(118, 751), region(765, 787));
        private final List<MeasureRegion> fitted =
                List.of(raw.get(0), region(118, 383), region(387, 751), raw.get(2));

        InlineTripleRun() throws Exception {
            this('3');
        }

        InlineTripleRun(char numerator) throws Exception {
            Arrays.fill(gray, (byte) 255);
            for (int y = TOP; y <= TOP + 4 * GAP; y += GAP) {
                ink(10, 790, y, y, 0);
                Arrays.fill(labels, y * WIDTH + 10, y * WIDTH + 791, OmrMeasurePostProcessor.STAFF);
            }
            var signature = InlineTripleMeterProofTest.signature(numerator, '4', true);
            for (int y = TOP; y <= TOP + 4 * GAP; y++) {
                for (int x = 108; x <= 159; x++) {
                    gray[y * WIDTH + x] = signature[y * 320 + x];
                    if ((gray[y * WIDTH + x] & 255) < 180 && (y - TOP) % GAP != 0)
                        labels[y * WIDTH + x] = x >= 123
                                ? OmrMeasurePostProcessor.CLEF_OR_KEY
                                : OmrMeasurePostProcessor.STEM_OR_REST;
                }
            }
            ink(756, 760, TOP, TOP + 4 * GAP, 0);
            ink(375, 377, 68, 162, 0);
            ink(377, 393, 66, 72, 0);
            ink(377, 393, 122, 128, 0);
        }

        private static MeasureRegion region(int left, int right) {
            return new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH,
                    (TOP - 30f) / HEIGHT, (TOP + 4 * GAP + 30f) / HEIGHT);
        }

        private void ink(int left, int right, int top, int bottom, int value) {
            for (int y = top; y <= bottom; y++)
                Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
        }

        private List<ScoreNoteEvent> notes(int unresolved) {
            var notes = new ArrayList<ScoreNoteEvent>();
            for (int i = 0; i < 12; i++) {
                float position = (185 + i * 50 - 118) / 633f;
                var upper = new ScoreNoteEvent(1, position, 7, 0, 1, 69f / HEIGHT,
                        false, 0, 2, 2, 0).withStemDirection(i >= 4 && i < 4 + unresolved ? 0 : -1);
                var lower = new ScoreNoteEvent(1, position, 0, 0, 1, 125f / HEIGHT,
                        false, 0, 2, 2, 0).withStemDirection(-1);
                notes.add(upper);
                notes.add(lower);
            }
            return notes;
        }

        private List<MeasureRegion> preserve(List<ScoreNoteEvent> notes) {
            return PrintedMeasureRhythmGuard.preserveCompleteRuns(
                    raw, fitted, notes, List.of(), labels, gray, WIDTH, HEIGHT);
        }
    }

    @Test
    public void inferredMidpointMovesToTheVisibleGrandStaffBarline() {
        byte[] g = paper();
        for (int y = 160; y <= 480; y++) g[y * 1000 + 630] = 0;
        var aligned =
                PrintedMeasureRhythmGuard.alignPrintedSeparators(
                        raw, fitted, notes(1, true, 1), g, 1000, 800);
        assertEquals(.628f, aligned.get(2).right(), .0001f);
        assertEquals(.632f, aligned.get(3).left(), .0001f);
        assertEquals(raw.get(0), aligned.get(0));
    }

    @Test
    public void singleStaffStemCannotMoveAnInferredBoundary() {
        byte[] g = paper();
        for (int y = 160; y <= 480; y++) g[y * 1000 + 630] = 0;
        assertEquals(
                fitted,
                PrintedMeasureRhythmGuard.alignPrintedSeparators(
                        raw, fitted, notes(1, false, 1), g, 1000, 800));
    }
}
