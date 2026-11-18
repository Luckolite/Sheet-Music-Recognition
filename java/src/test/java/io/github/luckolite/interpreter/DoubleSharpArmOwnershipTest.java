// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class DoubleSharpArmOwnershipTest {
    private static final int WIDTH = 390;
    private static final int HEIGHT = 220;
    private static final int CROSS_X = 196;
    private static final int CROSS_Y = 71;
    private static final int HEAD_X = 227;
    private static final int HEAD_Y = 81;
    private static final int NOTEHEAD = OmrMeasurePostProcessor.NOTEHEAD;
    private static final int SYMBOL = OmrMeasurePostProcessor.SYMBOL;
    private static final int CLEF_OR_KEY = OmrMeasurePostProcessor.CLEF_OR_KEY;

    @Test
    public void accidentalArmCannotSupplyAnExtraAttack() {
        var plate = new Plate();
        var notes = plate.notes(plate.gray);
        assertFalse(plate.hasArmAttack(notes));
        assertEquals(1, notes.size());
        assertEquals(ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP, notes.get(0).writtenAccidental());
    }

    @Test
    public void missingInkInOnePlaneRetainsIndependentHeadEvidence() {
        var plate = new Plate();
        byte[] incomplete = plate.gray.clone();
        for (int y = CROSS_Y; y < CROSS_Y + 8; y++) {
            Arrays.fill(incomplete, y * WIDTH + CROSS_X, y * WIDTH + CROSS_X + 9, (byte) 255);
        }
        assertTrue(plate.hasArmAttack(plate.notes(incomplete)));
    }

    @Test
    public void aSmallAttackWithItsOwnShaftSurvives() {
        var plate = new Plate();
        int shaft = CROSS_X + 18;
        for (int y = CROSS_Y - 42; y <= CROSS_Y + 9; y++) {
            plate.dot(shaft, y, 1);
            plate.dot(shaft + 1, y, 1);
        }
        for (int x = CROSS_X + 16; x < shaft; x++) {
            plate.gray[(CROSS_Y + 4) * WIDTH + x] = 0;
        }
        assertTrue(
                DoubleSharpGlyph.matchesRaw(
                        plate.gray,
                        WIDTH,
                        HEIGHT,
                        CROSS_X,
                        CROSS_Y,
                        CROSS_X + 17,
                        CROSS_Y + 17,
                        16));
        assertTrue(plate.hasArmAttack(plate.notes(plate.gray)));
    }

    @Test
    public void onlyTheTwoPlaneCrossProducesALiteralMark() {
        var plate = new Plate();
        var actual = plate.analyze(plate.gray);
        assertEquals(
                List.of(new SourceLocalAccidental.PrintedMark(0, 3)),
                plate.printed(actual, actual.notes(), actual.notes()));

        byte[] erased = plate.gray.clone();
        for (int y = CROSS_Y; y < CROSS_Y + 8; y++) {
            Arrays.fill(erased, y * WIDTH + CROSS_X, y * WIDTH + CROSS_X + 9, (byte) 255);
        }
        var read = plate.analyze(erased);
        assertTrue(plate.printed(read, read.notes(), read.notes()).isEmpty());
    }

    @Test
    public void copiedOriginsAndDifferentRetainedPitchCannotReuseTheMark() {
        var plate = new Plate();
        var actual = plate.analyze(plate.gray);
        var notes = actual.notes();
        var owned = notes.get(0);
        var copy = owned.withOctaveShift(owned.octaveShift());
        assertEquals(owned, copy);
        assertFalse(owned == copy);
        assertTrue(plate.printed(actual, List.of(copy), notes).isEmpty());
        assertTrue(plate.printed(actual, notes, List.of(owned.withOctaveShift(1))).isEmpty());
        assertTrue(plate.printed(actual, notes, List.of(owned, owned)).isEmpty());
    }

    @Test
    public void nestedCrossCropsNeedTheSameTwoPlaneWaist() {
        var plate = new Plate();
        var outer = new DoubleSharpGlyph.Crop(45, 151, 62, 168);
        var inner = new DoubleSharpGlyph.Crop(48, 154, 59, 165);
        paintCross(plate, outer);
        assertTrue(sourceCross(plate, outer));
        assertTrue(sourceCross(plate, inner));
        assertTrue(
                DoubleSharpGlyph.sameNestedInk(
                        plate.gray, plate.gray, WIDTH, HEIGHT, outer, inner, 16));
        byte[] erasedOriginal = plate.gray.clone();
        for (int row = 155; row <= 164; row++)
            Arrays.fill(erasedOriginal, row * WIDTH + 51, row * WIDTH + 56, (byte) 255);
        assertFalse(
                DoubleSharpGlyph.sameNestedInk(
                        plate.gray, erasedOriginal, WIDTH, HEIGHT, outer, inner, 16));
    }

    @Test
    public void twoOverlappingCropsWithIndependentlyCompleteCrossesRemainDistinct() {
        var plate = new Plate();
        var first = new DoubleSharpGlyph.Crop(45, 151, 62, 168);
        var second = new DoubleSharpGlyph.Crop(58, 164, 75, 181);
        paintCross(plate, first);
        paintCross(plate, second);
        assertTrue(first.right() > second.left() && first.bottom() > second.top());
        assertTrue(sourceCross(plate, first));
        assertTrue(sourceCross(plate, second));
        assertFalse(
                DoubleSharpGlyph.sameNestedInk(
                        plate.gray, plate.gray, WIDTH, HEIGHT, first, second, 16));
        assertFalse(
                DoubleSharpGlyph.sameNestedInk(
                        plate.gray, plate.gray, WIDTH, HEIGHT, second, first, 16));
    }

    @Test
    public void staffJoinedCrossNeedsItsOwnArmsInBothPlanes() {
        var plate = new Plate();
        int centerX = 315, centerY = 136, crossX = 282;
        paintCross(plate, new DoubleSharpGlyph.Crop(crossX, 127, crossX + 17, 144));
        for (int y : new int[] {128, 144})
            for (int x = 245; x <= 320; x++) plate.dot(x, y, SYMBOL);
        for (int y = centerY - 8; y <= centerY + 8; y++) {
            for (int x = centerX - 11; x <= centerX + 11; x++) {
                double dx = (x - centerX) / 11.0, dy = (y - centerY) / 8.0;
                if (dx * dx + dy * dy <= 1) plate.dot(x, y, NOTEHEAD);
            }
        }
        for (int y = centerY + 3; y <= centerY + 47; y++) {
            plate.dot(centerX - 10, y, 1);
            plate.dot(centerX - 9, y, 1);
        }
        var actual = plate.analyze(plate.gray);
        assertEquals(2, actual.notes().size());
        assertEquals(ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP, actual.notes().get(1).writtenAccidental());
        assertEquals(List.of(new SourceLocalAccidental.PrintedMark(0, 3),
                             new SourceLocalAccidental.PrintedMark(1, 3)),
                plate.printed(actual, actual.notes(), actual.notes()));

        byte[] partial = plate.gray.clone();
        for (int y = 129; y <= 135; y++)
            Arrays.fill(partial, y * WIDTH + crossX, y * WIDTH + crossX + 9, (byte) 255);
        var erased = plate.analyze(partial);
        assertEquals(2, erased.notes().size());
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, erased.notes().get(1).writtenAccidental());
        assertEquals(List.of(new SourceLocalAccidental.PrintedMark(0, 3)),
                plate.printed(erased, erased.notes(), erased.notes()));
    }

    private static boolean sourceCross(Plate plate, DoubleSharpGlyph.Crop crop) {
        return DoubleSharpGlyph.matchesRaw(
                plate.gray,
                WIDTH,
                HEIGHT,
                crop.left(),
                crop.top(),
                crop.right(),
                crop.bottom(),
                16);
    }

    private static void paintCross(Plate plate, DoubleSharpGlyph.Crop crop) {
        for (int y = 0; y < 18; y++)
            for (int x = 0; x < 18; x++)
                if (Math.abs(x - y) <= 3 || Math.abs(x + y - 17) <= 3)
                    plate.dot(crop.left() + x, crop.top() + y, SYMBOL);
    }

    private static final class Plate {
        private static final List<MeasureRegion> BARS =
                List.of(new MeasureRegion(.02f, .96f, .25f, .8f));
        final byte[] gray = new byte[WIDTH * HEIGHT];
        final byte[] labels = new byte[WIDTH * HEIGHT];

        Plate() {
            Arrays.fill(gray, (byte) 255);
            for (int y = 80; y <= 144; y += 16) for (int x = 16; x < WIDTH - 15; x++) dot(x, y, 4);
            for (int y = 0; y < 18; y++)
                for (int x = 0; x < 18; x++)
                    if (Math.abs(x - y) <= 4 || Math.abs(x + y - 17) <= 4) {
                        int label = x >= 9 && y < 8 ? NOTEHEAD : y < 10 ? SYMBOL : CLEF_OR_KEY;
                        dot(CROSS_X + x, CROSS_Y + y, label);
                    }
            for (int y = HEAD_Y - 8; y <= HEAD_Y + 8; y++)
                for (int x = HEAD_X - 11; x <= HEAD_X + 11; x++) {
                    double dx = (x - HEAD_X) / 11.0, dy = (y - HEAD_Y) / 8.0;
                    if (dx * dx + dy * dy <= 1) dot(x, y, NOTEHEAD);
                }
            for (int y = HEAD_Y + 3; y <= HEAD_Y + 49; y++) {
                dot(HEAD_X - 10, y, 1);
                dot(HEAD_X - 9, y, 1);
            }
        }

        OmrScoreInterpreter.Analysis analyze(byte[] source) {
            return OmrScoreInterpreter.analyze(labels, gray, WIDTH, HEIGHT, BARS, source);
        }

        List<ScoreNoteEvent> notes(byte[] source) {
            return analyze(source).notes();
        }

        List<SourceLocalAccidental.PrintedMark> printed(
                OmrScoreInterpreter.Analysis actual,
                List<ScoreNoteEvent> original,
                List<ScoreNoteEvent> retained) {
            return actual.clockProducerSource()
                    .localAccidentals()
                    .printedMarks(original, retained, BARS);
        }

        boolean hasArmAttack(List<ScoreNoteEvent> notes) {
            return notes.stream()
                    .anyMatch(n -> n.positionInMeasure() * .94 * WIDTH + .02 * WIDTH < HEAD_X - 14);
        }

        void dot(int x, int y, int label) {
            labels[y * WIDTH + x] = (byte) label;
            gray[y * WIDTH + x] = 0;
        }
    }
}
