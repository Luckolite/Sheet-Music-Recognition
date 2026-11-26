// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;

public final class TempoChangeDetectorTest {
    @Test
    public void onePrintedEquationOwnsAnOverlappingPartialOcrNumeral() {
        int width = 600, height = 240;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int x = 104; x <= 116; x++) {
            gray[48 * width + x] = 0;
            gray[54 * width + x] = 0;
            gray[48 * width + x + 240] = 0;
            gray[54 * width + x + 240] = 0;
        }
        var marks = TempoChangeDetector.detect(List.of(
                        new MeasureNumberReconciler.NumberToken(
                                60, 131f / width, 42f / height, 153f / width, 60f / height),
                        new MeasureNumberReconciler.NumberToken(
                                160, 120f / width, 42f / height, 158f / width, 60f / height),
                        new MeasureNumberReconciler.NumberToken(
                                90, 360f / width, 42f / height, 390f / width, 60f / height)),
                gray, width, height, List.of(new MeasureRegion(.15f, .9f, .27f, .48f)));
        assertEquals(List.of(160d, 90d), marks.stream().map(ScoreTempoChange::bpm).toList());
    }

    @Test
    public void verifiedMetronomeHeadCannotConsumeTheOpeningRest() {
        var bars = List.of(new MeasureRegion(0, 1, .3f, .8f));
        var glyph = new TempoChangeDetector.BeatGlyph(0, 20, 26, 4, 22, 23, 19);
        var alias = new ScoreNoteEvent(0, .23f, 12, 0, 1, .19f, false, 0, 0, 1, 1)
                .withLeadingRest(2);
        var entry = new ScoreNoteEvent(0, .50f, 0, 0, 1, .7f, false, 0, 0, 1, 1);
        var ledgerNote = new ScoreNoteEvent(0, .60f, 12, 0, 1, .24f, false, 0, 0, 1, 1);
        var notes = List.of(alias, entry, ledgerNote);
        assertEquals(List.of(entry.withLeadingRest(2), ledgerNote),
                TempoChangeDetector.withoutBeatGlyphs(notes, bars, List.of(glyph), 100, 100));
        assertEquals(notes, TempoChangeDetector.withoutBeatGlyphs(notes, bars, List.of(), 100, 100));
    }

    @Test
    public void openingTempoLineMayStartLeftOfTheFirstBar() {
        int w = 300, h = 240;
        byte[] gray = new byte[w * h];
        Arrays.fill(gray, (byte) 255);
        for (int x = 104; x <= 116; x++) {
            gray[48 * w + x] = 0;
            gray[54 * w + x] = 0;
        }
        var token =
                new MeasureNumberReconciler.NumberToken(
                        120, 120f / w, 42f / h, 150f / w, 60f / h, 65f / w);
        assertEquals(
                List.of(new ScoreTempoChange(0, 0, 120)),
                TempoChangeDetector.detect(
                        List.of(token),
                        gray,
                        w,
                        h,
                        List.of(new MeasureRegion(.40f, .9f, .27f, .48f))));
    }

    @Test
    public void outOfBoundsLaterTempoLineDoesNotJumpToAnotherRow() {
        int w = 300, h = 400;
        byte[] gray = new byte[w * h];
        Arrays.fill(gray, (byte) 255);
        for (int x = 104; x <= 116; x++) {
            gray[248 * w + x] = 0;
            gray[254 * w + x] = 0;
        }
        var token =
                new MeasureNumberReconciler.NumberToken(
                        120, 120f / w, 242f / h, 150f / w, 260f / h, 65f / w);
        assertTrue(
                TempoChangeDetector.detect(
                                List.of(token),
                                gray,
                                w,
                                h,
                                List.of(
                                        new MeasureRegion(.4f, .9f, .2f, .35f),
                                        new MeasureRegion(.4f, .9f, .67f, .85f)))
                        .isEmpty());
    }

    @Test
    public void longDirectionStartsAtItsWordsEvenOnTheTopSystem() {
        int w = 600, h = 400;
        byte[] gray = new byte[w * h];
        Arrays.fill(gray, (byte) 255);
        for (int x = 380; x <= 393; x++) {
            gray[48 * w + x] = 0;
            gray[54 * w + x] = 0;
        }
        var changes =
                TempoChangeDetector.detect(
                        List.of(
                                new MeasureNumberReconciler.NumberToken(
                                        88, 400f / w, 42f / h, 425f / w, 60f / h, 210f / w)),
                        gray,
                        w,
                        h,
                        List.of(
                                new MeasureRegion(.05f, .32f, .17f, .40f),
                                new MeasureRegion(.34f, .65f, .17f, .40f),
                                new MeasureRegion(.67f, .95f, .17f, .40f)));
        assertEquals(List.of(new ScoreTempoChange(1, 0, 88)), changes);
    }

    @Test
    public void connectedLetterStrokesBesideDigitsAreNotAnEqualsSign() {
        int width = 300, height = 240;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int x = 104; x <= 116; x++) {
            gray[48 * width + x] = 0;
            gray[54 * width + x] = 0;
        }
        for (int y = 48; y <= 54; y++) gray[y * width + 104] = 0;
        assertTrue(
                TempoChangeDetector.detect(
                                List.of(
                                        new MeasureNumberReconciler.NumberToken(
                                                101,
                                                120f / width,
                                                42f / height,
                                                150f / width,
                                                60f / height)),
                                gray,
                                width,
                                height,
                                List.of(new MeasureRegion(.2f, .9f, .27f, .48f)))
                        .isEmpty());
    }

    @Test
    public void titleDigitsFarAboveStaffAreNeverATempoEvenWithTwoInkBars() {
        int width = 300, height = 240;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int x = 104; x <= 116; x++) {
            gray[12 * width + x] = 0;
            gray[18 * width + x] = 0;
        }
        assertTrue(
                TempoChangeDetector.detect(
                                List.of(
                                        new MeasureNumberReconciler.NumberToken(
                                                101,
                                                120f / width,
                                                6f / height,
                                                150f / width,
                                                24f / height)),
                                gray,
                                width,
                                height,
                                List.of(new MeasureRegion(.2f, .9f, .4f, .48f)))
                        .isEmpty());
    }

    @Test
    public void noteEqualsNumberMarkBecomesATempoChange() {
        int width = 300, height = 240;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 0xff);
        for (int x = 104; x <= 116; x++) {
            gray[48 * width + x] = 0;
            gray[54 * width + x] = 0;
        }
        List<ScoreTempoChange> changes =
                TempoChangeDetector.detect(
                        List.of(
                                new MeasureNumberReconciler.NumberToken(
                                        136,
                                        120f / width,
                                        42f / height,
                                        150f / width,
                                        60f / height)),
                        gray,
                        width,
                        height,
                        List.of(
                                new MeasureRegion(.20f, .55f, .27f, .48f),
                                new MeasureRegion(.56f, .90f, .27f, .48f)));

        assertEquals(List.of(new ScoreTempoChange(0, 0f, 136)), changes);
    }

    @Test
    public void barePrintedMeasureNumberIsNotATempo() {
        int width = 300, height = 240;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 0xff);
        assertTrue(
                TempoChangeDetector.detect(
                                List.of(
                                        new MeasureNumberReconciler.NumberToken(
                                                85, .18f, .20f, .22f, .25f)),
                                gray,
                                width,
                                height,
                                List.of(new MeasureRegion(.20f, .90f, .27f, .48f)))
                        .isEmpty());
    }

    @Test
    public void inStaffGlyphWithTwoHorizontalStrokesCannotBecome47Bpm() {
        int width = 300, height = 240;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 0xff);
        for (int x = 104; x <= 116; x++) {
            gray[72 * width + x] = 0;
            gray[78 * width + x] = 0;
        }
        List<ScoreTempoChange> changes =
                TempoChangeDetector.detect(
                        List.of(
                                new MeasureNumberReconciler.NumberToken(
                                        47,
                                        120f / width,
                                        66f / height,
                                        150f / width,
                                        84f / height)),
                        gray,
                        width,
                        height,
                        List.of(
                                new MeasureRegion(.2f, .9f, .27f, .40f),
                                new MeasureRegion(.2f, .9f, .45f, .60f)));
        assertTrue("Clef-like OCR must not override the selected tempo", changes.isEmpty());
    }

    private static byte[] numberedTempoPage(int width, int height, boolean equation) {
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int y : new int[] {90, 99, 108, 117, 126})
            for (int x = 30; x < width - 20; x++) gray[y * width + x] = 0;
        if (equation)
            for (int x = 104; x <= 116; x++) {
                gray[48 * width + x] = 0;
                gray[54 * width + x] = 0;
            }
        return gray;
    }

    @Test
    public void numberedStaffPageWithoutTempoEquationsRetainsEmptyResultAndPixels() {
        for (int width : new int[] {300, 600}) {
            byte[] gray = numberedTempoPage(width, 240, false), before = gray.clone();
            var tokens =
                    List.of(
                            new MeasureNumberReconciler.NumberToken(3, .18f, .20f, .22f, .25f),
                            new MeasureNumberReconciler.NumberToken(85, .35f, .20f, .39f, .25f),
                            new MeasureNumberReconciler.NumberToken(401, .58f, .20f, .62f, .25f));
            assertEquals(
                    List.of(),
                    TempoChangeDetector.detect(
                            tokens,
                            gray,
                            width,
                            240,
                            List.of(new MeasureRegion(.1f, .93f, .375f, .6f))));
            assertArrayEquals(before, gray);
        }
    }

    @Test
    public void delayedFirstTempoEquationRetainsOrderDeduplicationBitsAndPixels() {
        int width = 300, height = 240;
        byte[] gray = numberedTempoPage(width, height, true), before = gray.clone();
        var first =
                new MeasureNumberReconciler.NumberToken(
                        120, 120f / width, 42f / height, 150f / width, 60f / height, 65f / width);
        var second =
                new MeasureNumberReconciler.NumberToken(
                        136, 120f / width, 42f / height, 150f / width, 60f / height, 65f / width);
        var tokens =
                List.of(
                        new MeasureNumberReconciler.NumberToken(7, .2f, .2f, .3f, .3f),
                        new MeasureNumberReconciler.NumberToken(85, .7f, .03f, .8f, .1f),
                        first,
                        first,
                        second,
                        new MeasureNumberReconciler.NumberToken(401, .2f, .2f, .3f, .3f));
        var expected = List.of(new ScoreTempoChange(0, 0, 120), new ScoreTempoChange(0, 0, 136));
        var actual =
                TempoChangeDetector.detect(
                        tokens,
                        gray,
                        width,
                        height,
                        List.of(new MeasureRegion(.4f, .9f, .27f, .6f)));
        assertEquals(expected, actual);
        assertArrayEquals(before, gray);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(
                    Float.floatToRawIntBits(expected.get(i).positionInMeasure()),
                    Float.floatToRawIntBits(actual.get(i).positionInMeasure()));
            assertEquals(
                    Double.doubleToRawLongBits(expected.get(i).bpm()),
                    Double.doubleToRawLongBits(actual.get(i).bpm()));
            assertEquals(
                    Double.doubleToRawLongBits(expected.get(i).beatUnit()),
                    Double.doubleToRawLongBits(actual.get(i).beatUnit()));
        }
    }

    @Test
    public void stackedSectionCaptionOwnsTheFirstBarBeyondTheMargin() {
        int width = 600, height = 420;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int top : new int[] {120, 320})
            for (int line = 0; line < 5; line++)
                Arrays.fill(gray, (top + line * 12) * width + 24,
                        (top + line * 12) * width + 560, (byte) 0);
        for (int y = 174; y <= 203; y++) gray[y * width + 209] = 0;
        for (int y = 196; y <= 204; y++)
            Arrays.fill(gray, y * width + 197, y * width + 210, (byte) 0);
        for (int y : new int[] {190, 196})
            Arrays.fill(gray, y * width + 216, y * width + 229, (byte) 0);
        for (int y = 181; y <= 204; y++)
            Arrays.fill(gray, y * width + 235, y * width + 242, (byte) 0);
        var digits = new MeasureNumberReconciler.NumberToken(
                100, 235f / width, 181f / height, 261f / width, 205f / height, 45f / width);
        var caption = new PlayingTechniqueDetector.Word(
                "poco rubato = 100", 45f / width, 176f / height, 261f / width, 208f / height);
        var heading = new PlayingTechniqueDetector.Word(
                "Second Scene", 47f / width, 207f / height, 220f / width, 232f / height);
        var measureNumber = new PlayingTechniqueDetector.Word(
                "32", 30f / width, 286f / height, 48f / width, 307f / height);
        var bars = List.of(new MeasureRegion(105f / width, 560f / width, 120f / height, 169f / height),
                new MeasureRegion(105f / width, 211f / width, 320f / height, 389f / height),
                new MeasureRegion(212f / width, 560f / width, 320f / height, 389f / height));
        assertEquals(List.of(new ScoreTempoChange(1, 0, 100)),
                TempoChangeDetector.detectWithWords(List.of(digits), List.of(caption, heading, measureNumber),
                        gray, width, height, bars));
        assertTrue(TempoChangeDetector.detectWithWords(List.of(digits), List.of(caption, measureNumber),
                gray, width, height, bars).isEmpty());
        assertTrue(TempoChangeDetector.detectWithWords(List.of(digits), List.of(caption, heading),
                gray, width, height, bars).isEmpty());
    }

    @Test
    public void equationAboveLedgerLinesRequiresTheAlignedRaisedNote() {
        int width = 300, height = 300;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int y = 30; y <= 60; y++) gray[y * width + 81] = 0;
        for (int y = 56; y <= 64; y++)
            Arrays.fill(gray, y * width + 69, y * width + 83, (byte) 0);
        for (int y : new int[] {50, 56})
            Arrays.fill(gray, y * width + 100, y * width + 113, (byte) 0);
        for (int y = 43; y <= 64; y++)
            Arrays.fill(gray, y * width + 120, y * width + 125, (byte) 0);
        var mark = new PlayingTechniqueDetector.Word(
                "85", 120f / width, 38f / height, 145f / width, 83f / height);
        var staff = new PlayingTechniqueDetector.Staff(191, 255, 16, 0, 1);
        var bars = List.of(new MeasureRegion(.2f, .9f, .6f, .93f));
        for (float noteY : new float[] {121f, 197f}) {
            var note = new ScoreNoteEvent(0, 1f / 7, 16, 0, 1, noteY / height,
                    false, 0, 0, 0, 4f);
            var score = new ScorePageInterpretation(bars, List.of(note), 1, List.of(), List.of(),
                    List.of(new ScoreMeterChange(0, 4, 4)), List.of(), List.of(), List.of());
            var replay = TempoChangeDetector.recoverAboveLedgerTempos(
                    score, List.of(mark), List.of(staff), gray, width, height);
            assertEquals(noteY < 150 ? List.of(new ScoreTempoChange(0, 0, 85)) : List.of(),
                    replay.tempoChanges());
        }
    }


    private static byte[] independentEquation() {
        byte[] gray = new byte[600 * 240];
        Arrays.fill(gray, (byte) 255);
        for (int y = 31; y <= 61; y++) gray[y * 600 + 344] = 0;
        for (int y = 56; y <= 64; y++)
            Arrays.fill(gray, y * 600 + 331, y * 600 + 346, (byte) 0);
        for (int y : new int[] {48, 54})
            Arrays.fill(gray, y * 600 + 372, y * 600 + 388, (byte) 0);
        for (int y = 41; y <= 63; y++)
            Arrays.fill(gray, y * 600 + 396, y * 600 + 402, (byte) 0);
        return gray;
    }

    @Test public void namedEquationNearTheBoundaryUsesTheFollowingBarStart() {
        var previous = new MeasureRegion(120f / 600, 300f / 600, 90f / 240, 153f / 240);
        var next = new MeasureRegion(318f / 600, 540f / 600, 90f / 240, 153f / 240);
        var digit = new MeasureNumberReconciler.NumberToken(
                145, 396f / 600, 41f / 240, 425f / 600, 64f / 240, 290f / 600);
        var caption = new PlayingTechniqueDetector.Word(
                "Vivace J = 145", 290f / 600, 38f / 240, 429f / 600, 66f / 240);
        var marks = TempoChangeDetector.detectWithWords(List.of(digit), List.of(caption),
                independentEquation(), 600, 240, List.of(previous, next));
        assertEquals(List.of(new ScoreTempoChange(1, 0, 145)), marks);
    }

    @Test public void countedRestCarriesTheAccelerationToTheSeparateNextEquation() {
        var rest = new MeasureRegion(120f / 600, 300f / 600, 90f / 240, 153f / 240);
        var next = new MeasureRegion(318f / 600, 540f / 600, 90f / 240, 153f / 240);
        var bars = List.of(rest, rest, next);
        var digit = new MeasureNumberReconciler.NumberToken(
                145, 396f / 600, 41f / 240, 425f / 600, 64f / 240, 138f / 600);
        var caption = new PlayingTechniqueDetector.Word(
                "accellerando J = 145", 138f / 600, 38f / 240, 429f / 600, 66f / 240);
        var gray = independentEquation();
        var marks = TempoChangeDetector.detectWithWords(
                List.of(digit), List.of(caption), gray, 600, 240, bars);
        assertEquals(List.of(new ScoreTempoChange(2, 0, 145)), marks);
        var input = new ScorePageInterpretation(bars, List.of(), 1, List.of(), marks,
                List.of(new ScoreMeterChange(0, 12, 8)),
                List.of(ScoreRestEvent.fullMeasure(0, .35f, .5f, .04f, 0, 1),
                        ScoreRestEvent.fullMeasure(1, .35f, .5f, .04f, 0, 1)))
                .withMultiRestGroups(List.of(new ScoreMultiRestGroup(0, 2, 0, 1)));
        var staff = new PlayingTechniqueDetector.Staff(90, 138, 12, 0, 1);
        var result = PrintedTempoSpans.apply(input, List.of(caption), List.of(staff), gray, 600, 240);
        assertEquals(1, result.expressiveEvents().size());
        var ramp = result.expressiveEvents().get(0);
        assertEquals(ScoreExpressiveEvent.Kind.ACCELERANDO, ramp.kind());
        assertEquals(new ScoreAnchor(0, 0), ramp.start().orElseThrow());
        assertEquals(new ScoreAnchor(2, 0), ramp.end().orElseThrow());
    }
}
